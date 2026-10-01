package dev.wareworks.core.address;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link NetworkGeometry} is {@link AisleGeometry} for a warehouse that may bend. The first thing it has to prove is
 * that it did not move anything: a network of one branch must answer exactly what one aisle answered before M21,
 * position for position and index for index.
 */
class NetworkGeometryTest {
    private static final int LENGTH = 5;
    private static final int HEIGHT = 3;
    /** An L: east from the dock, then south from the corner block. */
    private static final NetworkGeometry CORNER = new NetworkGeometry(List.of(
            new BranchGeometry(0, 0, 0, Heading.EAST, 4),
            new BranchGeometry(1, 4, 0, Heading.SOUTH, 3)), HEIGHT);

    @Test
    void oneBranchIsExactlyTheAisleItWas() {
        AisleGeometry aisle = AisleGeometry.of(LENGTH, HEIGHT);
        NetworkGeometry network = NetworkGeometry.of(aisle, Heading.EAST);
        assertEquals(1, network.branchCount());
        assertEquals(aisle.rackPositionCount(), network.rackPositionCount());
        assertEquals(aisle.rackPositions(), network.rackPositions(), "the same positions in the same order");
        for (int index = 0; index < aisle.rackPositionCount(); index++) {
            RackPosition position = aisle.rackPosition(index);
            assertEquals(position, network.rackPosition(index));
            assertEquals(index, network.indexOf(position));
            assertTrue(network.contains(position));
        }
        assertEquals(aisle, network.aisleGeometry(0));
    }

    @Test
    void validatesItsBranches() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkGeometry(List.of(), HEIGHT),
                "a warehouse always has the branch at its dock");
        assertThrows(IllegalArgumentException.class,
                () -> new NetworkGeometry(List.of(new BranchGeometry(1, 0, 0, Heading.EAST, 1)), HEIGHT),
                "branch indexes are the list positions");
        assertThrows(IllegalArgumentException.class,
                () -> new NetworkGeometry(List.of(new BranchGeometry(0, 2, 0, Heading.EAST, 1)), HEIGHT),
                "the first branch starts at the dock");
        assertThrows(IllegalArgumentException.class, () -> NetworkGeometry.single(Heading.EAST, 0, 0),
                "and the mast is at least one level high");
        assertThrows(IllegalArgumentException.class, () -> tooManyBranches(StorageAddress.AISLE_COUNT + 1),
                "every branch needs an address letter");
        tooManyBranches(StorageAddress.AISLE_COUNT);
    }

    @Test
    void countsAndOrdersEveryBranch() {
        int positions = (4 + 1) + (3 + 1);
        assertEquals(2 * positions * HEIGHT, CORNER.rackPositionCount());
        List<RackPosition> all = CORNER.rackPositions();
        assertEquals(CORNER.rackPositionCount(), all.size());
        for (int index = 1; index < all.size(); index++)
            assertTrue(RackPosition.ORDER.compare(all.get(index - 1), all.get(index)) < 0,
                    "the list is in RackPosition.ORDER at " + index);
        for (int index = 0; index < all.size(); index++)
            assertEquals(index, CORNER.indexOf(all.get(index)), "index round trip at " + index);
        // Branch is the first key, so one branch's positions are contiguous and the first branch comes first.
        assertEquals(0, all.getFirst().branch());
        assertEquals(1, all.getLast().branch());
        assertEquals(-1, CORNER.indexOf(new RackPosition(2, 0, 0, Side.LEFT)), "a branch it does not have");
        assertEquals(-1, CORNER.indexOf(new RackPosition(1, 4, 0, Side.LEFT)), "past the end of a branch");
        assertEquals(-1, CORNER.indexOf(new RackPosition(0, 0, HEIGHT, Side.LEFT)), "above the mast");
    }

    @Test
    void findsTheBlockTwoBranchesShare() {
        assertEquals(List.of(new BranchLink(0, 4, 1, 0)), CORNER.links(),
                "the corner block is position 4 of the first branch and 0 of the second");
        assertTrue(NetworkGeometry.single(Heading.EAST, LENGTH, HEIGHT).links().isEmpty(),
                "a warehouse that never bends has no link");
        BranchLink link = CORNER.links().getFirst();
        assertEquals(1, link.other(0));
        assertEquals(0, link.other(1));
        assertEquals(-1, link.other(2), "a branch that is not one of its two");
        assertEquals(4, link.positionOn(0));
        assertEquals(0, link.positionOn(1));

        // Two branches that cross without touching a shared position are not linked.
        NetworkGeometry apart = new NetworkGeometry(List.of(
                new BranchGeometry(0, 0, 0, Heading.EAST, 2),
                new BranchGeometry(1, 6, 0, Heading.SOUTH, 3)), HEIGHT);
        assertTrue(apart.links().isEmpty(), "the second branch starts beyond the first one's end");
    }

    @Test
    void knowsItsAisleBlocks() {
        assertTrue(CORNER.isAisleBlock(0, 0), "the dock");
        assertTrue(CORNER.isAisleBlock(4, 0), "the corner block, on both branches");
        assertTrue(CORNER.isAisleBlock(4, 3), "the far end of the second branch");
        assertFalse(CORNER.isAisleBlock(4, 4), "one block past it");
        assertFalse(CORNER.isAisleBlock(3, 1), "and the block inside the bend is no aisle block");
    }

    /**
     * What a controller keeps when a discovery run was partial but the dock's own aisle is confirmed shorter than the
     * warehouse it knows: <b>that aisle shorter, and every other aisle exactly as it was</b>. Keeping the old length
     * instead let a crane drive over rails a player had broken (M21 review fix); keeping only the first aisle deleted
     * a comb's teeth because of one lateral read in an unloaded chunk (M22 review fix).
     */
    @Test
    void aShorterFirstBranchShortensThatAisleAndNothingElse() {
        assertEquals(new NetworkGeometry(List.of(
                new BranchGeometry(0, 0, 0, Heading.EAST, 2),
                new BranchGeometry(1, 4, 0, Heading.SOUTH, 3)), HEIGHT),
                CORNER.withFirstBranchLength(2),
                "the aisle at the dock over the rails that are really there, the other one untouched");
        assertEquals(2, CORNER.withFirstBranchLength(0).branchCount(),
                "even a warehouse whose first aisle is all gone keeps the aisles it says nothing about");
        assertEquals(0, CORNER.withFirstBranchLength(-3).firstBranch().length(),
                "and a length below zero is no exception a caller has to handle");

        assertSame(CORNER, CORNER.withFirstBranchLength(4), "the length it already has changes nothing");
        assertSame(CORNER, CORNER.withFirstBranchLength(9),
                "and this only ever shrinks an aisle: a longer one is not evidence of anything else");
        NetworkGeometry straight = NetworkGeometry.single(Heading.NORTH, LENGTH, HEIGHT);
        assertEquals(NetworkGeometry.single(Heading.NORTH, 1, HEIGHT), straight.withFirstBranchLength(1),
                "one aisle keeps its heading and its height and simply gets shorter");
        assertEquals(HEIGHT, CORNER.withFirstBranchLength(1).height(), "the mast is not touched");
    }

    /**
     * The case the old rule got wrong: a comb whose main run is confirmed shorter keeps every tooth, at its own index,
     * with its own origin — so no address of a block a player never touched changes. The teeth the shorter run really
     * disconnects are a <b>reachability</b> answer, not a deletion: they are no longer joined to the aisle at the dock,
     * which is what the crane, the planner and a member's goggles read ({@code RouteCosts#componentOf}).
     */
    @Test
    void aShorterMainRunKeepsTheTeethOfAComb() {
        NetworkGeometry comb = new NetworkGeometry(List.of(
                new BranchGeometry(0, 0, 0, Heading.EAST, 12),
                new BranchGeometry(1, 3, 0, Heading.SOUTH, 8),
                new BranchGeometry(2, 7, 0, Heading.SOUTH, 8),
                new BranchGeometry(3, 11, 0, Heading.SOUTH, 8)), HEIGHT);

        NetworkGeometry shorter = comb.withFirstBranchLength(8);
        assertEquals(4, shorter.branchCount(), "nothing is dropped and nothing renumbers");
        assertEquals(8, shorter.firstBranch().length(), "only the aisle a loaded block disproved got shorter");
        for (int branch = 1; branch < comb.branchCount(); branch++)
            assertEquals(comb.branch(branch), shorter.branch(branch), "tooth " + branch + " is untouched");
        // Every position of every tooth is still a position of the warehouse, so no record is forgotten.
        for (RackPosition rack : comb.rackPositions()) {
            if (rack.branch() != 0 || rack.x() <= 8)
                assertTrue(shorter.contains(rack), rack + " is still a position of the warehouse");
        }
        // The tooth beyond the shortened run no longer shares a block with it, which is the reachability answer.
        assertEquals(2, shorter.links().size(), "the teeth at 3 and 7 still meet the main run; the one at 11 does not");
    }

    private static NetworkGeometry tooManyBranches(int count) {
        List<BranchGeometry> branches = new ArrayList<>(count);
        for (int index = 0; index < count; index++)
            branches.add(new BranchGeometry(index, index == 0 ? 0 : index * 2, 0,
                    index == 0 ? Heading.EAST : Heading.SOUTH, 1));
        return new NetworkGeometry(branches, HEIGHT);
    }
}
