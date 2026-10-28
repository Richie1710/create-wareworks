package dev.wareworks.core.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The traffic proof of ADR-075 §6 checked by seeded random runs on every shape of {@link TestNetworks}: lines with a
 * dock at each end and with both at one end, tees, crosses, rings, combs, ladders, dead-end trees and the crossed tee.
 * <p>
 * Every run gives random jobs — two random stops each, on any block but another crane's dock — to random cranes at
 * random speeds, with transfers that keep the arm out and idle cranes that drive to their home points. Asserted on
 * <b>every tick</b> ({@link TrafficSimulator}): no cell in two claims (I1), every crane inside its claim (I2), no block
 * shared, and every crane able to reach its dock after every pass (I3). Asserted <b>per run</b>: every job completes
 * within a bound (no starvation), and no stretch of ticks passes in which a crane waits while nothing moves and no
 * transfer runs (no deadlock). And the same run with the cranes handed to the ledger in reverse order gives the
 * identical trace, which is what makes the result independent of the order block entities tick in.
 */
class TrafficSimulationPropertyTest {
    private static final int RUNS = 300;
    private static final int JOBS = 12;
    private static final double JOB_CHANCE = 0.15;
    private static final long JOB_BOUND = 1_500;
    private static final int WINDOW = 40;
    private static final long MAX_TICKS = 20_000;

    static java.util.List<TestNetworks.Shape> shapes() {
        return TestNetworks.all();
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void everyJobFinishesAndNoTwoCranesEverMeet(TestNetworks.Shape shape) {
        Random seeds = new Random(20261027L + shape.name().hashCode());
        int moved = 0;
        int yielded = 0;
        for (int run = 0; run < RUNS; run++) {
            long seed = seeds.nextLong();
            int cranes = 2 + new Random(seed).nextInt(shape.cranes() - 1);
            TrafficSimulator simulator = new TrafficSimulator(shape, cranes, seed, false);
            simulator.run(JOBS, JOB_CHANCE, JOB_BOUND, WINDOW, MAX_TICKS);
            moved += simulator.moves;
            yielded += simulator.yields;
            if (run % 10 == 0) {
                TrafficSimulator mirror = new TrafficSimulator(shape, cranes, seed, true);
                mirror.run(JOBS, JOB_CHANCE, JOB_BOUND, WINDOW, MAX_TICKS);
                assertEquals(simulator.trace, mirror.trace,
                        "the cranes handed over in reverse order drive differently on " + shape);
            }
        }
        assertTrue(moved > 0, "nothing ever moved on " + shape);
        if (!shape.name().startsWith("line-same-end"))
            assertTrue(yielded > 0, "no crane ever moved aside on " + shape + ": the runs test too little");
    }
}
