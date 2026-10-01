package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;

/**
 * Routing over a warehouse whose rails <b>split and close on themselves</b>: tees, crosses, rings, aisles the rails no
 * longer reach, and what all of it costs (M22, issue #2, ADR-033).
 * <p>
 * Two claims are tested as hard as the shapes themselves. First, <b>a warehouse that does not split answers exactly
 * what it answered before</b>: {@link #theCostOfEveryChainIsTheOneTheChainWalkAlwaysGave} runs the walk M21 used, over
 * every pair of points of every chain shape, against the search that replaced it. Second, <b>the same build always
 * plans the same job</b>: {@link #twoIdenticalBuildsAnswerIdenticallyEverywhere} builds one shape twice and compares
 * every route and every cost.
 */
class RouteCostsTest {
    private static final double PENALTY = 1.0;
    private static final double EPSILON = 1.0E-9;

    // --- the shapes ----------------------------------------------------------------------------------------------

    /** One straight aisle of 12 rails running east from the dock: every warehouse before M21. */
    private static final NetworkGeometry STRAIGHT = NetworkGeometry.single(Heading.EAST, 12, 6);

    /** An L: 8 east from the dock, then 5 south from the corner at {@code (8, 0)}. */
    private static final NetworkGeometry CORNER = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 5)), 6);

    /** A U: the L, and 8 west from {@code (8, 5)} back alongside the first aisle. */
    private static final NetworkGeometry U_SHAPE = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 8, 0, Heading.SOUTH, 5),
            new BranchGeometry(2, 8, 5, Heading.WEST, 8)), 6);

    /**
     * A T: 8 east from the dock, and a side aisle running 5 south out of the <b>middle</b> of it at {@code (4, 0)}.
     * The rail at {@code (4, 0)} has three neighbours, which is exactly what M21 refused to walk into.
     */
    private static final NetworkGeometry TEE = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 4, 0, Heading.SOUTH, 5)), 6);

    /**
     * A cross: the same main aisle, and a side aisle that runs through it from {@code (4, -3)} to {@code (4, 3)}. The
     * rail at {@code (4, 0)} has four neighbours.
     */
    private static final NetworkGeometry CROSS = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 8),
            new BranchGeometry(1, 4, -3, Heading.SOUTH, 6)), 6);

    /**
     * A ring hanging off the aisle at the dock, square enough that the two ways round a pair of points on its sides
     * cost exactly the same: 6 east, then a loop {@code (2,0) → (2,4) → (6,4) → (6,0)}.
     */
    private static final NetworkGeometry RING = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 6),
            new BranchGeometry(1, 2, 0, Heading.SOUTH, 4),
            new BranchGeometry(2, 2, 4, Heading.EAST, 4),
            new BranchGeometry(3, 6, 4, Heading.NORTH, 4)), 6);

    /** The same ring pulled long on its far side, so one way round is 8 blocks shorter than the other. */
    private static final NetworkGeometry LOPSIDED_RING = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 6),
            new BranchGeometry(1, 2, 0, Heading.SOUTH, 8),
            new BranchGeometry(2, 2, 8, Heading.EAST, 4),
            new BranchGeometry(3, 6, 8, Heading.NORTH, 8)), 6);

    /**
     * A long way round with two turns beside a staircase short cut with four: {@code (0,0) → (12,0) → (12,6) → (0,6)}
     * is 26 blocks and 2 turns, while {@code (0,0) → (2,0) → (2,3) → (4,3) → (4,6)} is 10 blocks and 4 turns. Which one
     * is cheaper is decided by {@code crane.turnPenaltyBlocks} and nothing else.
     */
    private static final NetworkGeometry SHORT_CUT = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, 12),
            new BranchGeometry(1, 12, 0, Heading.SOUTH, 6),
            new BranchGeometry(2, 12, 6, Heading.WEST, 12),
            new BranchGeometry(3, 2, 0, Heading.SOUTH, 3),
            new BranchGeometry(4, 2, 3, Heading.EAST, 2),
            new BranchGeometry(5, 4, 3, Heading.SOUTH, 3)), 6);

    /** A comb: one main run of 30 rails with 15 side aisles of 8 hanging off it, the shape issue #2 is about. */
    private static final NetworkGeometry COMB = comb(15);

    // --- a T -----------------------------------------------------------------------------------------------------

    @Test
    void aSideAisleOutOfTheMiddleOfAnotherIsAnOrdinaryAisle() {
        RouteCosts costs = RouteCosts.of(TEE, PENALTY);
        assertEquals(1, costs.junctionCount(), "the rail the two aisles share");
        assertTrue(costs.reachable(0, 1));
        assertTrue(costs.reachable(1, 0));

        CraneRoute out = costs.route(0, 0.0, 1, 5.0).orElseThrow();
        assertEquals(List.of(new CraneRoute.Leg(0, 0.0, 4.0, Heading.EAST),
                new CraneRoute.Leg(1, 0.0, 5.0, Heading.SOUTH)), out.legs());
        assertEquals(9.0, out.blocks(), EPSILON, "4 down the main aisle, 5 down the side one");
        assertEquals(10.0, out.costBlocks(PENALTY), EPSILON);
        assertEquals(OptionalDouble.of(10.0), costs.costBlocks(0, 0.0, 1, 5.0));
    }

    @Test
    void aJunctionIsReachedFromBothEndsOfTheAisleItSitsIn() {
        RouteCosts costs = RouteCosts.of(TEE, PENALTY);
        // The far end of the main aisle: M21 could not even discover these rails, because the walk stopped at (4, 0).
        assertEquals(OptionalDouble.of(8.0), costs.costBlocks(0, 8.0, 1, 3.0), "4 back, 3 down, one turn");
        assertEquals(OptionalDouble.of(7.0), costs.costBlocks(1, 2.0, 0, 0.0), "2 up, 4 west, one turn");
        assertEquals(OptionalDouble.of(1.0), costs.costBlocks(0, 4.0, 1, 0.0),
                "the shared rail itself: no travel at all, but the machine still has to turn");
    }

    @Test
    void drivingPastAJunctionIsNotATurn() {
        RouteCosts costs = RouteCosts.of(TEE, PENALTY);
        CraneRoute through = costs.route(0, 1.0, 0, 7.0).orElseThrow();
        assertTrue(through.isStraight(), "the side aisle changes nothing about driving the main one");
        assertEquals(0, through.turns());
        assertEquals(OptionalDouble.of(6.0), costs.costBlocks(0, 1.0, 0, 7.0));
        assertEquals(0, costs.cellsPerQuestion(0, 0), "and it reads no cost at all");
    }

    // --- a cross -------------------------------------------------------------------------------------------------

    @Test
    void anAisleThatRunsThroughAnotherIsOneAisleOnBothSides() {
        RouteCosts costs = RouteCosts.of(CROSS, PENALTY);
        assertEquals(1, costs.junctionCount());
        CraneRoute across = costs.route(1, 0.0, 1, 6.0).orElseThrow();
        assertTrue(across.isStraight(), "it is one aisle, so crossing the other one is not a turn");
        assertEquals(6.0, across.blocks(), EPSILON);

        // Both halves of the crossing aisle are reached, which is what "at most one aisle per axis" buys.
        assertEquals(OptionalDouble.of(7.0), costs.costBlocks(0, 1.0, 1, 0.0), "3 east, turn, 3 north");
        assertEquals(OptionalDouble.of(7.0), costs.costBlocks(0, 1.0, 1, 6.0), "3 east, turn, 3 south");
        assertEquals(OptionalDouble.of(2.0), costs.costBlocks(0, 5.0, 1, 3.0), "the crossing rail itself");
    }

    @Test
    void everyPairOfPointsOfABranchingWarehouseHasARouteThatDrivesWhatItCosts() {
        for (NetworkGeometry network : List.of(TEE, CROSS, RING, LOPSIDED_RING, SHORT_CUT, COMB)) {
            RouteCosts costs = RouteCosts.of(network, PENALTY);
            for (int from = 0; from < network.branchCount(); from++) {
                for (int to = 0; to < network.branchCount(); to++) {
                    double fromX = network.branch(from).length() * 0.5;
                    double toX = network.branch(to).length();
                    CraneRoute route = costs.route(from, fromX, to, toX).orElseThrow();
                    assertEquals(from, route.fromBranch());
                    assertEquals(to, route.toBranch());
                    assertEquals(fromX, route.fromX(), EPSILON);
                    assertEquals(toX, route.toX(), EPSILON);
                    assertEquals(costs.costBlocks(from, fromX, to, toX).orElseThrow(), route.costBlocks(PENALTY),
                            EPSILON, network + ": " + from + "@" + fromX + " → " + to + "@" + toX);
                }
            }
        }
    }

    // --- a ring --------------------------------------------------------------------------------------------------

    @Test
    void aRingWithTwoEqualWaysRoundAlwaysDrivesTheSameOne() {
        RouteCosts costs = RouteCosts.of(RING, PENALTY);
        assertEquals(4, costs.junctionCount());

        // (2, 2) to (6, 2): 2 + 4 + 2 blocks and two turns whichever way round the loop it goes.
        CraneRoute route = costs.route(1, 2.0, 3, 2.0).orElseThrow();
        assertEquals(8.0, route.blocks(), EPSILON);
        assertEquals(2, route.turns());
        assertEquals(OptionalDouble.of(10.0), costs.costBlocks(1, 2.0, 3, 2.0));
        // Of two equally cheap ways that turn as often, the one priced at the lower position on the aisle the
        // machine stands on: leaving aisle 1 at position 0 beats leaving it at position 4.
        assertEquals(List.of(new CraneRoute.Leg(1, 2.0, 0.0, Heading.SOUTH),
                new CraneRoute.Leg(0, 2.0, 6.0, Heading.EAST),
                new CraneRoute.Leg(3, 4.0, 2.0, Heading.NORTH)), route.legs());
        assertEquals(List.of(1, 0, 3), route.legs().stream().map(CraneRoute.Leg::branch).toList());

        // Whatever a turn costs, both ways turn twice, so the decision never moves.
        for (double penalty : List.of(0.0, 0.5, 4.0, 16.0)) {
            CraneRoute again = RouteCosts.of(RING, penalty).route(1, 2.0, 3, 2.0).orElseThrow();
            assertEquals(route.legs(), again.legs(), "turn penalty " + penalty);
            assertEquals(8.0 + 2 * penalty, again.costBlocks(penalty), EPSILON);
        }
    }

    @Test
    void aRingWithOneShorterWayRoundTakesIt() {
        RouteCosts costs = RouteCosts.of(LOPSIDED_RING, PENALTY);
        CraneRoute route = costs.route(1, 2.0, 3, 6.0).orElseThrow();
        assertEquals(List.of(new CraneRoute.Leg(1, 2.0, 0.0, Heading.SOUTH),
                new CraneRoute.Leg(0, 2.0, 6.0, Heading.EAST),
                new CraneRoute.Leg(3, 8.0, 6.0, Heading.NORTH)), route.legs());
        assertEquals(8.0, route.blocks(), EPSILON, "2 up, 4 across, 2 down — not 6 + 4 + 6 the long way round");
        assertEquals(OptionalDouble.of(10.0), costs.costBlocks(1, 2.0, 3, 6.0));
        assertEquals(2, route.turns(), "both ways turn twice, so this is the blocks and nothing else");
    }

    @Test
    void whatAQuarterTurnCostsDecidesWhichWayRound() {
        // Twelve east, six south, eight west: 26 blocks and two turns. Or the staircase: 10 blocks and four turns.
        assertEquals(OptionalDouble.of(10.0), RouteCosts.of(SHORT_CUT, 0.0).costBlocks(0, 0.0, 2, 8.0),
                "a free turn makes the short cut simply the shortest way");
        assertEquals(OptionalDouble.of(14.0), RouteCosts.of(SHORT_CUT, 1.0).costBlocks(0, 0.0, 2, 8.0));
        assertEquals(OptionalDouble.of(42.0), RouteCosts.of(SHORT_CUT, 8.0).costBlocks(0, 0.0, 2, 8.0),
                "at eight blocks a turn the two cost the same, and the one that turns less wins");
        assertEquals(OptionalDouble.of(46.0), RouteCosts.of(SHORT_CUT, 10.0).costBlocks(0, 0.0, 2, 8.0),
                "beyond that the long way round is genuinely cheaper");

        assertEquals(List.of(0, 3, 4, 5, 2), branchesOf(SHORT_CUT, 1.0), "the staircase, while a turn is cheap");
        assertEquals(List.of(0, 1, 2), branchesOf(SHORT_CUT, 8.0), "the long way round, once a turn is dear");
        assertEquals(List.of(0, 1, 2), branchesOf(SHORT_CUT, 10.0));
    }

    private static List<Integer> branchesOf(NetworkGeometry network, double penalty) {
        return RouteCosts.of(network, penalty).route(0, 0.0, 2, 8.0).orElseThrow().legs().stream()
                .map(CraneRoute.Leg::branch).toList();
    }

    // --- a comb: goods from every aisle reach one block -----------------------------------------------------------

    /**
     * The shape issue #2 is about, and the one that pins the hand-overs: a trip from the first side aisle to the last
     * drives <b>past</b> thirteen junctions of the main run without turning at any of them, so it is three legs and
     * two turns however many side aisles there are.
     */
    @Test
    void aTripAcrossACombIsThreeLegsHoweverManyAislesItDrivesPast() {
        RouteCosts costs = RouteCosts.of(COMB, PENALTY);
        CraneRoute route = costs.route(1, 4.0, 15, 4.0).orElseThrow();
        assertEquals(List.of(new CraneRoute.Leg(1, 4.0, 0.0, Heading.SOUTH),
                new CraneRoute.Leg(0, 2.0, 30.0, Heading.EAST),
                new CraneRoute.Leg(15, 0.0, 4.0, Heading.SOUTH)), route.legs());
        assertEquals(2, route.turns(), "out of its aisle, along the main run, into the other one");
        assertEquals(36.0, route.blocks(), EPSILON, "4 out, 28 across, 4 in");
        assertEquals(OptionalDouble.of(38.0), costs.costBlocks(1, 4.0, 15, 4.0));

        // And the near aisle really is cheaper than the far one, which is what keeps one crane from criss-crossing.
        assertEquals(OptionalDouble.of(12.0), costs.costBlocks(1, 4.0, 2, 4.0), "4 out, 2 across, 4 in, two turns");
        assertEquals(OptionalDouble.of(15.0), costs.costBlocks(0, 2.0, 8, 0.0),
                "14 down the main run and one turn into the eighth side aisle");
        assertEquals(OptionalDouble.of(6.0), costs.costBlocks(0, 2.0, 0, 8.0), "straight down the main run");
    }

    // --- an aisle with no route ----------------------------------------------------------------------------------

    @Test
    void anAisleTheRailsNoLongerReachIsMarkedAndPlannedForByNobody() {
        // The comb with the rail that joined its last side aisle taken out: the aisle is still there, still indexed,
        // still full of chests — and the crane cannot get to it.
        List<BranchGeometry> branches = new ArrayList<>(COMB.branches());
        int cut = branches.size() - 1;
        BranchGeometry stranded = branches.get(cut);
        branches.set(cut, new BranchGeometry(cut, stranded.originDx(), stranded.originDz() + 2, stranded.heading(),
                stranded.length()));
        RouteCosts costs = RouteCosts.of(new NetworkGeometry(branches, COMB.height()), PENALTY);

        assertFalse(costs.reachable(0, cut), "no rails join it to the main run");
        assertFalse(costs.reachable(cut, 0));
        assertFalse(costs.canDrive(0, 0.0, cut, 3.0));
        assertEquals(OptionalDouble.empty(), costs.costBlocks(0, 0.0, cut, 3.0));
        assertEquals(Optional.empty(), costs.route(0, 0.0, cut, 3.0));
        assertTrue(costs.componentOf(0) != costs.componentOf(cut), "it is its own part of the world");
        assertEquals(-1, costs.componentOf(COMB.branchCount()), "and an aisle this warehouse has not is nowhere");

        // Everything that is still joined keeps working, and the stranded aisle can still be driven along.
        assertTrue(costs.reachable(1, 2));
        assertEquals(OptionalDouble.of(6.0), costs.costBlocks(cut, 1.0, cut, 7.0), "along the aisle it stands on");
        assertTrue(costs.canDrive(cut, 1.0, cut, 7.0));
    }

    @Test
    void aPositionOutsideItsOwnAisleIsOnNoRoute() {
        RouteCosts costs = RouteCosts.of(TEE, PENALTY);
        assertEquals(Optional.empty(), costs.route(1, 5.5, 0, 0.0), "the side aisle is 5 long");
        assertEquals(Optional.empty(), costs.route(0, -0.5, 1, 0.0));
        assertEquals(Optional.empty(), costs.route(0, Double.NaN, 1, 0.0));
        assertEquals(OptionalDouble.empty(), costs.costBlocks(0, 0.0, 9, 0.0));
        assertFalse(costs.canDrive(0, 0.0, 9, 0.0));
        assertFalse(costs.canDrive(9, 0.0, 9, 1.0), "not even along an aisle the warehouse does not have");
    }

    @Test
    void aTurnPriceThatIsNotANumberIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RouteCosts.of(TEE, -1.0));
        assertThrows(IllegalArgumentException.class, () -> RouteCosts.of(TEE, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> RouteCosts.of(TEE, Double.POSITIVE_INFINITY));
    }

    // --- a warehouse that does not split answers what it always answered -----------------------------------------

    /**
     * The identity that has to hold for every world built before M22: on a chain there is exactly one route, so the
     * search must return precisely what M21's walk returned. The walk is reimplemented here rather than called, so
     * this stays an oracle even once the walk itself is gone.
     */
    @Test
    void theCostOfEveryChainIsTheOneTheChainWalkAlwaysGave() {
        for (NetworkGeometry network : List.of(STRAIGHT, CORNER, U_SHAPE)) {
            for (double penalty : List.of(0.0, 1.0, 4.0)) {
                RouteCosts costs = RouteCosts.of(network, penalty);
                for (int from = 0; from < network.branchCount(); from++) {
                    for (int to = 0; to < network.branchCount(); to++) {
                        for (double fromX = 0.0; fromX <= network.branch(from).length(); fromX += 0.5) {
                            for (double toX = 0.0; toX <= network.branch(to).length(); toX += 0.5) {
                                String where = network + " @" + penalty + ": " + from + "@" + fromX + " → " + to + "@"
                                        + toX;
                                OptionalDouble walked = chainWalkBlocks(network, from, fromX, to, toX, penalty);
                                OptionalDouble searched = costs.costBlocks(from, fromX, to, toX);
                                assertEquals(walked.isPresent(), searched.isPresent(), where);
                                if (walked.isEmpty())
                                    continue;
                                assertEquals(walked.getAsDouble(), searched.getAsDouble(), EPSILON, where);
                                assertEquals(walked.getAsDouble(),
                                        costs.route(from, fromX, to, toX).orElseThrow().costBlocks(penalty), EPSILON,
                                        where);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void oneAisleIsStillTheDistanceAndNothingElse() {
        RouteCosts costs = RouteCosts.of(STRAIGHT, PENALTY);
        assertEquals(0, costs.junctionCount());
        assertEquals(0, costs.nodeCount());
        assertEquals(OptionalDouble.of(7.0), costs.costBlocks(0, 2.0, 0, 9.0));
        assertEquals(OptionalDouble.of(7.0), costs.costBlocks(0, 9.0, 0, 2.0), "backwards is not a turn");
        assertEquals(OptionalDouble.of(0.0), costs.costBlocks(0, 4.0, 0, 4.0));
        assertTrue(costs.route(0, 2.0, 0, 9.0).orElseThrow().isStraight());
        assertEquals(0, costs.rowsComputed(), "a warehouse of one aisle never costs a single pass");
    }

    // --- the same build always plans the same job ----------------------------------------------------------------

    @Test
    void twoIdenticalBuildsAnswerIdenticallyEverywhere() {
        for (NetworkGeometry built : List.of(RING, LOPSIDED_RING, SHORT_CUT, COMB)) {
            // The same shape described twice over, from separate objects, and costed separately.
            NetworkGeometry again = new NetworkGeometry(List.copyOf(built.branches()), built.height());
            RouteCosts first = RouteCosts.of(built, PENALTY);
            RouteCosts second = RouteCosts.of(again, PENALTY);
            assertEquals(first.links(), second.links());
            for (int from = 0; from < built.branchCount(); from++) {
                for (int to = 0; to < built.branchCount(); to++) {
                    assertEquals(first.componentOf(from), second.componentOf(from));
                    for (double x : List.of(0.0, 1.0, 2.0, 3.0)) {
                        if (x > built.branch(from).length() || x > built.branch(to).length())
                            continue;
                        String where = built + ": " + from + "@" + x + " → " + to + "@" + x;
                        assertEquals(first.costBlocks(from, x, to, x), second.costBlocks(from, x, to, x), where);
                        assertEquals(first.route(from, x, to, x), second.route(from, x, to, x), where);
                    }
                }
            }
        }
    }

    @Test
    void askingTheSameQuestionTwiceAnswersTheSameThing() {
        RouteCosts costs = RouteCosts.of(SHORT_CUT, PENALTY);
        Optional<CraneRoute> once = costs.route(0, 0.0, 2, 8.0);
        for (int i = 0; i < 20; i++)
            assertEquals(once, costs.route(0, 0.0, 2, 8.0), "a cache may never change an answer");
    }

    // --- what a planning pass costs ------------------------------------------------------------------------------

    /**
     * The bound the design promised for step two: a planning pass derives at most one single-source pass per junction
     * of the network — <b>ever</b>, not per pass — and then reads a handful of integers per candidate. The comb is the
     * shape that makes it due: one main run with fifteen side aisles, every one of which holds candidates.
     */
    @Test
    void aWholePlanningPassOverACombCostsOnePassPerJunctionAndCountedIntegersPerCandidate() {
        RouteCosts costs = RouteCosts.of(COMB, PENALTY);
        assertEquals(15, costs.junctionCount(), "one rail shared per side aisle");
        assertEquals(30, costs.nodeCount());
        assertEquals(15, costs.junctionsOn(0), "every side aisle meets the main run");
        assertEquals(1, costs.junctionsOn(1));
        assertEquals(0, costs.rowsComputed(), "nothing is derived until something is asked");

        // A planning pass: every candidate is ranked by what it costs to fetch from and to deliver to, which is the
        // ten travel questions JobPlanner asks per candidate.
        int candidatesPerAisle = 4;
        int questionsPerCandidate = 10;
        long cellsRead = 0;
        int questions = 0;
        for (int branch = 0; branch < COMB.branchCount(); branch++) {
            for (int candidate = 0; candidate < candidatesPerAisle; candidate++) {
                double x = candidate * 2.0;
                for (int question = 0; question < questionsPerCandidate; question++) {
                    int other = question % COMB.branchCount();
                    assertTrue(costs.costBlocks(branch, x, other, 1.0).isPresent());
                    cellsRead += costs.cellsPerQuestion(branch, other);
                    questions++;
                }
            }
        }
        assertEquals(COMB.branchCount() * candidatesPerAisle * questionsPerCandidate, questions);
        assertTrue(costs.rowsComputed() <= costs.nodeCount(),
                "one single-source pass per junction node at the very most, got " + costs.rowsComputed());
        assertTrue(cellsRead <= 4_000,
                "a whole pass over a 16-aisle comb reads a few thousand integers, got " + cellsRead);

        // And the second pass over the same unchanged rails derives nothing at all.
        int derived = costs.rowsComputed();
        for (int branch = 0; branch < COMB.branchCount(); branch++) {
            for (int other = 0; other < COMB.branchCount(); other++)
                assertTrue(costs.costBlocks(branch, 1.0, other, 1.0).isPresent());
        }
        assertEquals(derived, costs.rowsComputed(), "costs are derived when the rails change and at no other time");
    }

    /**
     * The hand-over the route really takes, against the junction the trip was <b>priced</b> at — the two are not the
     * same thing, and the tie-break never claims they are (M22 review fix).
     * <p>
     * On this comb both junctions of the main run price the trip at exactly 8 blocks: leaving at {@code x = 2} and
     * handing over onto aisle 1 is 2 + 2 blocks + one turn + 2, and leaving at {@code x = 4} onto aisle 2 is 4 + 0 + a
     * turn + 2. The route that is built drives the main run to {@code x = 4} and hands over onto <b>aisle 2</b>, at the
     * same cost and with the same single turn, whatever a turn is worth — so a rule phrased as "the one that hands over
     * onto the lower-numbered aisle first" would name an aisle this route never touches.
     */
    @Test
    void theRouteHandsOverWhereItReallyDoesAndNotWhereItWasPriced() {
        NetworkGeometry built = comb(2); // main run 4 east, teeth south at x = 2 and x = 4
        for (double penalty : List.of(0.0, 0.5, PENALTY, 2.0, 8.0)) {
            RouteCosts costs = RouteCosts.of(built, penalty);
            CraneRoute route = costs.route(0, 0.0, 2, 2.0).orElseThrow();
            assertEquals(List.of(new CraneRoute.Leg(0, 0.0, 4.0, Heading.EAST),
                    new CraneRoute.Leg(2, 0.0, 2.0, Heading.SOUTH)), route.legs(), "turn penalty " + penalty);
            assertEquals(2, route.legs().get(1).branch(), "the first hand-over of the route that is driven");
            assertEquals(1, route.turns());
            assertEquals(6.0, route.blocks(), EPSILON, "4 along the main run, 2 down the tooth");
            // And the cost the planner ranked it by is the cost of exactly that route.
            assertEquals(costs.costBlocks(0, 0.0, 2, 2.0).orElseThrow(), route.costBlocks(penalty), EPSILON);
        }
    }

    /**
     * A table belongs to whoever holds the shape, and deriving is paid once per holder: nine warehouses kept side by
     * side cost nine derivations and no more, however often they are asked about.
     * <p>
     * This is the shape of the M22 review fix. {@code RouteTable.of} used to hand back a table out of a static
     * eight-slot cache that evicted by a plain counter, so exactly this access pattern — nine live shapes in a round
     * robin — missed <b>every single time</b> and re-derived the links and every single-source pass of a whole
     * warehouse per planning pass. There is no cache any more: the holder is the cache.
     */
    @Test
    void everyShapeIsCostedOnceByWhoeverHoldsIt() {
        int shapes = 9; // one more than the eight slots the old static cache had
        List<RouteTable> held = new ArrayList<>(shapes);
        for (int shape = 0; shape < shapes; shape++)
            held.add(RouteTable.of(comb(shape + 2), PENALTY));

        int[] after = new int[shapes];
        for (int lap = 0; lap < 5; lap++) {
            for (int shape = 0; shape < shapes; shape++) {
                RouteTable table = held.get(shape);
                for (int branch = 0; branch < table.branchCount(); branch++)
                    assertTrue(table.routeBlocks(0, 0.0, branch, 1.0, PENALTY).isPresent());
                int derived = table.costs(PENALTY).rowsComputed();
                if (lap == 0)
                    after[shape] = derived;
                else
                    assertEquals(after[shape], derived,
                            "shape " + shape + " is costed when its rails change and at no other time");
            }
        }

        // Two tables of the same shape are the same answer and share nothing: a holder keeps its own, and asking for
        // one is never a lookup of somebody else's.
        NetworkGeometry shape = comb(3);
        RouteTable mine = RouteTable.of(shape);
        RouteTable yours = RouteTable.of(shape);
        assertNotSame(mine, yours, "no global cache hands the same table to two holders");
        assertEquals(mine, yours, "but they describe the same rails");
        assertEquals(mine.junctionCount(), yours.junctionCount());
        assertEquals(mine.routeBlocks(0, 0.0, 1, 2.0, PENALTY), yours.routeBlocks(0, 0.0, 1, 2.0, PENALTY));

        RouteCosts priced = mine.costs(PENALTY);
        assertSame(priced, mine.costs(PENALTY), "the costs at one price are derived once per table");
        assertEquals(PENALTY, priced.turnPenaltyBlocks());
        assertSame(mine.costs(), mine.costs(RouteTable.TURNS_FREE), "and so are the ones with a free turn");
        assertEquals(RouteTable.TURNS_FREE, mine.costs().turnPenaltyBlocks());
        assertEquals(mine.routeBlocks(0, 0.0, 1, 2.0, PENALTY), priced.costBlocks(0, 0.0, 1, 2.0));
        assertSame(priced, mine.costs(PENALTY), "asking the free ones in between changes nothing");
    }

    // --- fixtures and the M21 oracle -----------------------------------------------------------------------------

    /** One main run of {@code teeth * 2} rails with a side aisle of 8 hanging off every second rail of it. */
    private static NetworkGeometry comb(int teeth) {
        List<BranchGeometry> branches = new ArrayList<>(teeth + 1);
        branches.add(BranchGeometry.first(Heading.EAST, teeth * 2));
        for (int tooth = 1; tooth <= teeth; tooth++)
            branches.add(new BranchGeometry(tooth, tooth * 2, 0, Heading.SOUTH, 8));
        return new NetworkGeometry(branches, 6);
    }

    /**
     * What a route cost before M22: the chain walked branch by branch, the links crossed in the order a breadth-first
     * walk over the branch list finds them, blocks summed per leg and one turn charged per hand-over. On a chain there
     * is only one route, so this is the whole of the answer; this is M21's {@code RouteModel} and nothing else.
     */
    private static OptionalDouble chainWalkBlocks(NetworkGeometry network, int fromBranch, double fromX, int toBranch,
            double toX, double turnPenaltyBlocks) {
        if (!isOn(network, fromBranch, fromX) || !isOn(network, toBranch, toX))
            return OptionalDouble.empty();
        double from = clamp(network, fromBranch, fromX);
        double to = clamp(network, toBranch, toX);
        if (fromBranch == toBranch)
            return OptionalDouble.of(Math.abs(to - from));
        List<BranchLink> path = linkPath(network, network.links(), fromBranch, toBranch);
        if (path == null)
            return OptionalDouble.empty();
        double blocks = 0.0;
        int branch = fromBranch;
        double at = from;
        for (BranchLink link : path) {
            blocks += Math.abs(link.positionOn(branch) - at);
            int next = link.other(branch);
            at = link.positionOn(next);
            branch = next;
        }
        blocks += Math.abs(to - at);
        return OptionalDouble.of(blocks + path.size() * turnPenaltyBlocks);
    }

    private static List<BranchLink> linkPath(NetworkGeometry network, List<BranchLink> links, int fromBranch,
            int toBranch) {
        int count = network.branchCount();
        BranchLink[] takenTo = new BranchLink[count];
        int[] cameFrom = new int[count];
        boolean[] seen = new boolean[count];
        seen[fromBranch] = true;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(fromBranch);
        while (!queue.isEmpty()) {
            int branch = queue.poll();
            if (branch == toBranch) {
                List<BranchLink> reversed = new ArrayList<>();
                for (int at = toBranch; at != fromBranch; at = cameFrom[at])
                    reversed.add(takenTo[at]);
                List<BranchLink> ordered = new ArrayList<>(reversed.size());
                for (int i = reversed.size() - 1; i >= 0; i--)
                    ordered.add(reversed.get(i));
                return ordered;
            }
            for (BranchLink link : links) {
                int next = link.other(branch);
                if (next < 0 || next >= count || seen[next])
                    continue;
                seen[next] = true;
                takenTo[next] = link;
                cameFrom[next] = branch;
                queue.add(next);
            }
        }
        return null;
    }

    private static boolean isOn(NetworkGeometry network, int branch, double x) {
        if (branch < 0 || branch >= network.branchCount() || !Double.isFinite(x))
            return false;
        return x >= -1.0E-6 && x <= network.branch(branch).length() + 1.0E-6;
    }

    private static double clamp(NetworkGeometry network, int branch, double x) {
        return Math.min(Math.max(x, 0.0), network.branch(branch).length());
    }
}
