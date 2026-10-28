package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CraneMotion;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.warehouse.CraneRoute;

/**
 * The proof of ADR-075 §6 checked by machine on tiny warehouses: a breadth-first search over <b>every</b> reachable
 * state of two and three cranes — positions, claims, commitments, wants and orders — under every choice of next stop,
 * on a line, a tee, a ring and a comb.
 * <p>
 * Between two ticks any one crane without a stop may be given any stop it may serve (any block but another crane's
 * dock), or none is. Every state reached is checked for the safety invariants, and from every state the world is then
 * left to itself with no new stops: every stop that is waiting must be reached within a bound, and no state may be a
 * fixed point with a waiting stop. The second check is memoised along the deterministic run, so each state is
 * drained once.
 */
class TrafficModelCheckTest {
    /** States the search may visit before it gives up and fails: the shapes are chosen to stay well below it. */
    private static final int MAX_STATES = 1_000_000;
    private static final CraneSpeeds ONE_BLOCK = new CraneSpeeds(1.0, 1.0, 1.0);

    static Stream<Arguments> cases() {
        return Stream.of(Arguments.of(TestNetworks.tinyLine(), 2), Arguments.of(TestNetworks.tinyTee(), 2),
                Arguments.of(TestNetworks.tinyRing(), 2), Arguments.of(TestNetworks.tinyComb(), 2),
                Arguments.of(TestNetworks.tinyTee(), 3), Arguments.of(TestNetworks.tinySquare(), 3),
                Arguments.of(TestNetworks.tinyComb3(), 3));
    }

    /** One global state: every crane's pose and stop, and the ledger. */
    private static final class World {
        final TestNetworks.Shape shape;
        final CranePose[] poses;
        final int[] goals;
        final TrafficLedger ledger;
        long time;

        World(TestNetworks.Shape shape, CranePose[] poses, int[] goals, TrafficLedger ledger, long time) {
            this.shape = shape;
            this.poses = poses;
            this.goals = goals;
            this.ledger = ledger;
            this.time = time;
        }

        World copy() {
            return new World(shape, poses.clone(), goals.clone(), ledger.copy(), time);
        }

        TrackGraph graph() {
            return shape.graph();
        }

        int cranes() {
            return poses.length;
        }

        int dock(int index) {
            return graph().docks()[index];
        }

        boolean on(int index, int cell) {
            int[] standing = graph().standingCells(poses[index].branch(), poses[index].x());
            return standing.length == 1 && standing[0] == cell;
        }

        boolean waiting() {
            for (int goal : goals) {
                if (goal != TrackGraph.NONE)
                    return true;
            }
            return false;
        }

        String key() {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < poses.length; i++)
                key.append(poses[i].branch()).append('/').append(poses[i].x()).append('>').append(goals[i])
                        .append(' ');
            return key.append(ledger.stateKey()).toString();
        }

        CranePose poseAt(int cell) {
            int branch = graph().branchOf(cell, 0);
            return CranePose.at(branch, graph().xOf(cell, 0), 0.0, Side.LEFT,
                    graph().network().branch(branch).heading());
        }

        /** One tick: a pass, then every crane through the gate, then arrivals, with every invariant checked. */
        void tick() {
            time++;
            List<TrafficCrane> inputs = new ArrayList<>();
            for (int i = 0; i < cranes(); i++) {
                Optional<TrafficWant> want = goals[i] == TrackGraph.NONE ? Optional.empty()
                        : Optional.of(new TrafficWant(TrafficWant.Kind.JOB, graph().branchOf(goals[i], 0),
                                graph().xOf(goals[i], 0)));
                inputs.add(new TrafficCrane(i + 1, poses[i].branch(), poses[i].x(), true, TrafficHealth.HEALTHY, want,
                        dock(i), TrackGraph.NONE));
            }
            ledger.pass(time, inputs);
            checkClaims();
            for (int i = 0; i < cranes(); i++) {
                CranePose pose = poses[i];
                CranePose target = goals[i] == TrackGraph.NONE ? pose : poseAt(goals[i]);
                CraneRoute motion = pose.branch() == target.branch() ? null
                        : shape.routes().route(pose.branch(), pose.x(), target.branch(), target.x()).orElse(null);
                switch (ledger.gate(i + 1, pose.branch(), pose.x(), true, target.branch(), target.x(), motion)) {
                    case NORMAL -> {
                        if (goals[i] != TrackGraph.NONE)
                            poses[i] = CraneMotion.step(pose, target, ONE_BLOCK, motion, 0.0);
                    }
                    case DETOUR -> {
                        TrafficLedger.Commitment commitment = ledger.commitment(i + 1).orElseThrow();
                        CraneRoute route = ledger.detourRoute(i + 1, pose.branch(), pose.x()).orElseThrow();
                        poses[i] = CraneMotion.step(pose, poseAt(commitment.stopCell()), ONE_BLOCK, route, 0.0);
                    }
                    case HOLD -> {
                    }
                }
                BitSet claim = ledger.claim(i + 1);
                for (int cell : graph().standingCells(poses[i].branch(), poses[i].x()))
                    if (!claim.get(cell))
                        fail("crane " + (i + 1) + " left its claim on " + shape + ": " + key());
            }
            BitSet seen = new BitSet();
            for (CranePose pose : poses) {
                for (int cell : graph().standingCells(pose.branch(), pose.x())) {
                    if (seen.get(cell))
                        fail("two cranes share a block on " + shape + ": " + key());
                    seen.set(cell);
                }
            }
            for (int i = 0; i < cranes(); i++) {
                if (goals[i] != TrackGraph.NONE && on(i, goals[i]))
                    goals[i] = TrackGraph.NONE;
            }
        }

        void checkClaims() {
            BitSet seen = new BitSet();
            List<int[]> settled = new ArrayList<>();
            int[] docks = new int[cranes()];
            boolean[] required = new boolean[cranes()];
            for (int i = 0; i < cranes(); i++) {
                BitSet claim = ledger.claim(i + 1);
                if (claim.intersects(seen))
                    fail("two claims share a cell on " + shape + ": " + key());
                seen.or(claim);
                int index = i;
                settled.add(ledger.commitment(i + 1).map(c -> new int[] {c.stopCell()})
                        .orElse(graph().standingCells(poses[index].branch(), poses[index].x())));
                docks[i] = dock(i);
                required[i] = true;
            }
            if (!EscapeCheck.cutOff(graph(), settled, docks, required).isEmpty())
                fail("a crane is cut off from its dock on " + shape + ": " + key());
        }
    }

    @ParameterizedTest(name = "{0} with {1} cranes")
    @MethodSource("cases")
    void everyWaitingStopIsReachedFromEveryReachableState(TestNetworks.Shape shape, int cranes) {
        TrackGraph graph = shape.graph();
        CranePose[] poses = new CranePose[cranes];
        int[] goals = new int[cranes];
        World start = new World(shape, poses,
                goals, new TrafficLedger(graph, shape.routes(), 0.0), 0);
        for (int i = 0; i < cranes; i++) {
            poses[i] = start.poseAt(graph.docks()[i]);
            goals[i] = TrackGraph.NONE;
        }
        Set<Fingerprint> seen = new HashSet<>();
        Deque<World> open = new ArrayDeque<>();
        seen.add(Fingerprint.of(start.key()));
        open.add(start);
        Set<Fingerprint> drained = new HashSet<>();
        int bound = 8 * graph.cellCount() * cranes;
        while (!open.isEmpty()) {
            World world = open.removeFirst();
            checkDrains(world, drained, bound);
            for (World next : successors(world)) {
                if (seen.add(Fingerprint.of(next.key()))) {
                    open.addLast(next);
                    if (seen.size() > MAX_STATES)
                        fail(shape + " with " + cranes + " cranes has more than " + MAX_STATES + " states");
                }
            }
        }
        System.out.println(shape + " with " + cranes + " cranes: " + seen.size() + " states");
        assertTrue(seen.size() > 50, shape + ": only " + seen.size() + " states, the search explored too little");
    }

    /**
     * A state key folded into 96 bits, so the search keeps hundreds of thousands of them without keeping the text: a
     * collision would merge two states, at odds far below one in a billion for the sizes searched here.
     */
    private record Fingerprint(long fnv, int java) {
        static Fingerprint of(String key) {
            long hash = 0xcbf29ce484222325L;
            for (int i = 0; i < key.length(); i++) {
                hash ^= key.charAt(i);
                hash *= 0x100000001b3L;
            }
            return new Fingerprint(hash, key.hashCode());
        }
    }

    /** Every choice of at most one new stop for one crane without a stop, each followed by one tick. */
    private static List<World> successors(World world) {
        List<World> next = new ArrayList<>();
        World idle = world.copy();
        idle.tick();
        next.add(idle);
        TrackGraph graph = world.graph();
        for (int i = 0; i < world.cranes(); i++) {
            if (world.goals[i] != TrackGraph.NONE)
                continue;
            for (int cell = 0; cell < graph.cellCount(); cell++) {
                if (world.on(i, cell) || (graph.isDock(cell) && cell != world.dock(i)))
                    continue;
                World given = world.copy();
                given.goals[i] = cell;
                given.tick();
                next.add(given);
            }
        }
        return next;
    }

    /**
     * Left to itself, the world reaches every waiting stop within {@code bound} ticks, and never stands still while a
     * stop waits. States already known to drain end the run early.
     */
    private static void checkDrains(World world, Set<Fingerprint> drained, int bound) {
        World run = world.copy();
        List<Fingerprint> path = new ArrayList<>();
        String text = run.key();
        Fingerprint key = Fingerprint.of(text);
        for (int step = 0; step <= bound; step++) {
            if (!run.waiting() || drained.contains(key)) {
                drained.addAll(path);
                drained.add(key);
                return;
            }
            path.add(key);
            run.tick();
            String next = run.key();
            if (next.equals(text))
                fail("deadlock: a waiting stop and a state that never changes on " + world.shape + ": " + text
                        + " goals " + Arrays.toString(run.goals));
            text = next;
            key = Fingerprint.of(next);
        }
        fail("a waiting stop is not reached within " + bound + " ticks on " + world.shape + " from " + world.key());
    }
}
