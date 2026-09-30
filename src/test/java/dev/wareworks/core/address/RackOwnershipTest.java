package dev.wareworks.core.address;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The rule the whole corner feature turns on (ADR-033): a block beside a warehouse is offered to every branch that
 * runs past it, and the member's own facing picks one.
 * <p>
 * The claim that has to hold is not "the answer is usually one" but <b>totality</b>: for any network and any block, at
 * most one candidate can be satisfied by a single facing, so a rack can never belong to two aisles and a corner can
 * never be dead. It holds because every candidate of a block has its own aisle block beside it — two candidates
 * reached through the same neighbour would share that block <i>and</i> its axis, and two branches of one axis through
 * one block are the same branch.
 */
class RackOwnershipTest {
    private static final int HEIGHT = 2;
    private static final int WINDOW = 8;

    /** An L: east from the dock to the corner at (4, 0), then south. */
    private static final NetworkGeometry CORNER = new NetworkGeometry(List.of(
            new BranchGeometry(0, 0, 0, Heading.EAST, 4),
            new BranchGeometry(1, 4, 0, Heading.SOUTH, 3)), HEIGHT);
    /** A T: the run east, with a spur south out of its middle. */
    private static final NetworkGeometry TEE = new NetworkGeometry(List.of(
            new BranchGeometry(0, 0, 0, Heading.EAST, 6),
            new BranchGeometry(1, 3, 0, Heading.SOUTH, 3)), HEIGHT);
    /** A cross: the run east, and a branch crossing it from north to south. */
    private static final NetworkGeometry CROSS = new NetworkGeometry(List.of(
            new BranchGeometry(0, 0, 0, Heading.EAST, 6),
            new BranchGeometry(1, 3, -2, Heading.SOUTH, 4)), HEIGHT);
    /** Two parallel aisles of one warehouse, two blocks apart, sharing the rack plane between them. */
    private static final NetworkGeometry PARALLEL = new NetworkGeometry(List.of(
            new BranchGeometry(0, 0, 0, Heading.EAST, 5),
            new BranchGeometry(1, 0, 2, Heading.EAST, 5)), HEIGHT);

    @Test
    void oneAisleOffersOnePositionPerBlockBesideIt() {
        NetworkGeometry straight = NetworkGeometry.single(Heading.EAST, 5, HEIGHT);
        assertEquals(List.of(new RackCandidate(0, 2, Side.RIGHT, Heading.SOUTH)), straight.candidates(2, 1));
        assertEquals(List.of(new RackCandidate(0, 2, Side.LEFT, Heading.NORTH)), straight.candidates(2, -1));
        assertTrue(straight.candidates(2, 0).isEmpty(), "the aisle line itself is no rack position");
        assertTrue(straight.candidates(2, 2).isEmpty(), "two blocks away is outside the aisle");
        assertTrue(straight.candidates(6, 1).isEmpty(), "and so is one position past its end");
    }

    /**
     * The case that breaks every rule based on "the free faces of the corner block": the block diagonally inside the
     * bend is beside a <b>straight</b> rail of both aisles and is no neighbour of the corner at all.
     */
    @Test
    void theBlockInsideABendBelongsToTheAisleItFaces() {
        List<RackCandidate> candidates = CORNER.candidates(3, 1);
        assertEquals(2, candidates.size(), "two aisles run past it");
        assertEquals(new RackCandidate(0, 3, Side.RIGHT, Heading.SOUTH), candidates.get(0));
        assertEquals(new RackCandidate(1, 1, Side.RIGHT, Heading.WEST), candidates.get(1));
        assertDistinctFacings(candidates);
    }

    @Test
    void everyFreeFaceOfACornerIsServedByExactlyOneAisle() {
        assertTrue(CORNER.candidates(4, 0).isEmpty(), "the corner block belongs to both aisles, so it is no rack");
        // Its four sides: two are aisle blocks, and each of the other two is one aisle's rack position.
        assertEquals(List.of(new RackCandidate(0, 4, Side.LEFT, Heading.NORTH)), CORNER.candidates(4, -1));
        assertEquals(List.of(new RackCandidate(1, 0, Side.LEFT, Heading.EAST)), CORNER.candidates(5, 0));
        assertTrue(CORNER.isAisleBlock(3, 0) && CORNER.isAisleBlock(4, 1), "the other two sides are the aisles");
    }

    /**
     * An aisle block of one branch is never a rack position of another. At every corner the rail just before the turn
     * is laterally beside the perpendicular branch, and the first rail after it is laterally beside the branch it came
     * from — so both of them, and the whole column above them, which is exactly where the crane's mast travels, were
     * offered as storage locations of the other aisle (M21 review fix).
     */
    @Test
    void anAisleBlockIsNeverARackPositionOfAnotherAisle() {
        assertTrue(CORNER.isAisleBlock(3, 0), "the rail just before the corner");
        assertTrue(CORNER.candidates(3, 0).isEmpty(), "is beside the second aisle and still no rack position of it");
        assertTrue(CORNER.isAisleBlock(4, 1), "the first rail after the corner");
        assertTrue(CORNER.candidates(4, 1).isEmpty(), "is beside the first aisle and still no rack position of it");
        assertFalse(CORNER.isRackColumn(3, 0), "so nothing in that column is addressable, at any height");

        // A chain may turn at position 1: the dock offers a connection towards its facing, so the rail in front of it
        // has only two. The dock block is then laterally beside the second branch - and WarehouseLayout.rackPos hands
        // the dock block out for every label of an aisle that is gone, on the argument that no question asked about it
        // can ever be answered with "yes".
        NetworkGeometry turnsAtOne = new NetworkGeometry(List.of(
                new BranchGeometry(0, 0, 0, Heading.EAST, 1),
                new BranchGeometry(1, 1, 0, Heading.SOUTH, 3)), HEIGHT);
        assertTrue(turnsAtOne.isAisleBlock(0, 0), "the dock");
        assertTrue(turnsAtOne.candidates(0, 0).isEmpty(), "is no rack position of the aisle that turns beside it");
        assertFalse(turnsAtOne.isRackColumn(0, 0), "so the dock is no rack column of its own warehouse");
        // The label itself stays a legal position of the network - the rack space is a rectangle per branch, and the
        // index bijection rests on that - so the controller has to refuse the block as well, which is what its probe
        // does before it reads anything (RackProbe.EMPTY for a column no branch may own).
        assertTrue(turnsAtOne.contains(new RackPosition(1, 0, 0, Side.RIGHT)),
                "the position a full scan still walks past");
    }

    @Test
    void junctionsNeedNoRuleOfTheirOwn() {
        // A T: the block inside each of its two bends resolves the same way a corner's does.
        assertDistinctFacings(TEE.candidates(2, 1));
        assertDistinctFacings(TEE.candidates(4, 1));
        assertEquals(2, TEE.candidates(2, 1).size(), "two aisles run past the block left of the spur");
        assertTrue(TEE.candidates(3, 0).isEmpty(), "the junction block itself is no rack position");
        // A cross: all four diagonal neighbours have two candidates, and the crossing block none.
        assertTrue(CROSS.candidates(3, 0).isEmpty(), "the crossing block is no rack position");
        for (int dx : new int[]{2, 4}) {
            for (int dz : new int[]{-1, 1}) {
                List<RackCandidate> candidates = CROSS.candidates(dx, dz);
                assertEquals(2, candidates.size(), "diagonal neighbour " + dx + "," + dz);
                assertDistinctFacings(candidates);
            }
        }
    }

    @Test
    void twoParallelAislesShareTheirRackPlaneByOppositeFacings() {
        List<RackCandidate> candidates = PARALLEL.candidates(2, 1);
        assertEquals(2, candidates.size());
        assertEquals(new RackCandidate(0, 2, Side.RIGHT, Heading.SOUTH), candidates.get(0));
        assertEquals(new RackCandidate(1, 2, Side.LEFT, Heading.NORTH), candidates.get(1));
        assertDistinctFacings(candidates);
    }

    /**
     * The property, over every shape above and every block in a window around it: no two candidates of one block want
     * the same facing, so exactly zero or one of them can ever be satisfied.
     */
    @Test
    void atMostOneCandidateCanEverBeSatisfied() {
        List<NetworkGeometry> networks = List.of(NetworkGeometry.single(Heading.NORTH, 4, HEIGHT), CORNER, TEE, CROSS,
                PARALLEL, spiral());
        int seenWithTwo = 0;
        for (NetworkGeometry network : networks) {
            for (int dx = -WINDOW; dx <= WINDOW; dx++) {
                for (int dz = -WINDOW; dz <= WINDOW; dz++) {
                    List<RackCandidate> candidates = network.candidates(dx, dz);
                    assertTrue(candidates.size() <= 4, "at most one candidate per neighbour at " + dx + "," + dz);
                    assertFalse(!candidates.isEmpty() && network.isAisleBlock(dx, dz),
                            "an aisle block is no rack position at " + dx + "," + dz);
                    assertDistinctFacings(candidates);
                    if (candidates.size() > 1)
                        seenWithTwo++;
                    for (RackCandidate candidate : candidates) {
                        RackPosition position = candidate.at(HEIGHT - 1);
                        assertTrue(network.contains(position), "a candidate is a real rack position: " + position);
                    }
                }
            }
        }
        assertTrue(seenWithTwo > 0, "the property would be empty if no block ever had two candidates");
    }

    /** A staircase of corners, so the property is not only tested on shapes that were designed for it. */
    private static NetworkGeometry spiral() {
        List<BranchGeometry> branches = new ArrayList<>();
        branches.add(new BranchGeometry(0, 0, 0, Heading.EAST, 3));
        branches.add(new BranchGeometry(1, 3, 0, Heading.SOUTH, 2));
        branches.add(new BranchGeometry(2, 3, 2, Heading.EAST, 3));
        branches.add(new BranchGeometry(3, 6, 2, Heading.NORTH, 4));
        return new NetworkGeometry(branches, HEIGHT);
    }

    /** Fails unless every candidate of one block requires its own facing of the member standing there. */
    private static void assertDistinctFacings(List<RackCandidate> candidates) {
        Map<Heading, RackCandidate> byFacing = new EnumMap<>(Heading.class);
        for (RackCandidate candidate : candidates) {
            RackCandidate clash = byFacing.put(candidate.awayFromBranch(), candidate);
            assertNull(clash, () -> "two candidates want the same facing: " + clash + " and " + candidate);
        }
    }
}
