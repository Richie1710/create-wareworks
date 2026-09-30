package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.Heading;

/**
 * Discovery, without a world: {@link RailGraph} walks a set of aisle blocks from the dock and cuts it into straight
 * branches ({@code docs/warehouse-system.md} §4, ADR-033).
 * <p>
 * Step one follows a <b>chain</b>. Every test here therefore checks two things at once: what the walk took, and what it
 * says about where it stopped — because the promise of this milestone is that a build this version cannot follow gives
 * a shorter <i>working</i> warehouse with the offending block named, never nothing and never something silently wrong.
 */
class RailGraphTest {
    private static final int HEIGHT = 4;
    private static final RailGraph.Limits LIMITS = new RailGraph.Limits(64, 26, 32, HEIGHT);

    @Test
    void followsAStraightAisle() {
        RailNetwork network = fixture().railsEast(1, 4).scan(Heading.EAST, LIMITS);
        assertEquals(1, network.branchCount());
        assertEquals(new BranchGeometry(0, 0, 0, Heading.EAST, 4), network.geometry().firstBranch());
        assertEquals(4, network.rails());
        assertEquals(NetworkStop.END, network.stop());
        assertTrue(network.isComplete());
        assertEquals(HEIGHT, network.geometry().height());
    }

    @Test
    void aDockWithoutRailsIsAWarehouseOfOneEmptyAisle() {
        RailNetwork network = fixture().scan(Heading.EAST, LIMITS);
        assertEquals(1, network.branchCount(), "there is always the branch at the dock");
        assertEquals(0, network.firstBranchLength());
        assertEquals(NetworkStop.END, network.stop());
    }

    @Test
    void theDockOffersAConnectionOnlyTowardsItsFacing() {
        Fixture fixture = fixture().rail(-1, 0).rail(0, 1).rail(0, -1).railsEast(1, 2);
        RailNetwork network = fixture.scan(Heading.EAST, LIMITS);
        assertEquals(2, network.firstBranchLength(), "only the rails in front of the dock");
        assertEquals(1, network.branchCount(), "a rail beside the dock is a rack position, not an aisle");
        assertEquals(2, network.rails());
    }

    @Test
    void cutsAnLIntoTwoBranchesSharingTheCorner() {
        RailNetwork network = fixture().railsEast(1, 4).railsSouth(4, 1, 3).scan(Heading.EAST, LIMITS);
        assertEquals(2, network.branchCount());
        assertEquals(new BranchGeometry(0, 0, 0, Heading.EAST, 4), network.geometry().branch(0));
        assertEquals(new BranchGeometry(1, 4, 0, Heading.SOUTH, 3), network.geometry().branch(1),
                "the second branch starts at the corner block, which is the end nearer the dock");
        assertEquals(List.of(new BranchLink(0, 4, 1, 0)), network.geometry().links());
        assertEquals(7, network.rails(), "the corner block is counted once, as the end of the first branch");
        assertEquals(NetworkStop.END, network.stop());
    }

    @Test
    void cutsAZigzagAtEveryTurnAndNowhereElse() {
        RailNetwork network = fixture().railsEast(1, 3).railsSouth(3, 1, 2).railsEastFrom(3, 2, 2)
                .scan(Heading.EAST, LIMITS);
        assertEquals(3, network.branchCount());
        assertEquals(new BranchGeometry(0, 0, 0, Heading.EAST, 3), network.geometry().branch(0));
        assertEquals(new BranchGeometry(1, 3, 0, Heading.SOUTH, 2), network.geometry().branch(1));
        assertEquals(new BranchGeometry(2, 3, 2, Heading.EAST, 2), network.geometry().branch(2));
        assertEquals(List.of(new BranchLink(0, 3, 1, 0), new BranchLink(1, 2, 2, 0)), network.geometry().links());
        assertEquals(NetworkStop.END, network.stop());
    }

    @Test
    void twoCollinearRailsAreAlwaysOneBranch() {
        // A long straight run through what would be a junction if the spur were there: still one branch, one letter.
        RailNetwork network = fixture().railsEast(1, 9).scan(Heading.EAST, LIMITS);
        assertEquals(1, network.branchCount());
        assertEquals(9, network.firstBranchLength());
    }

    @Test
    void stopsAtAClosedRailAndSaysSo() {
        RailNetwork network = fixture().railsEast(1, 4).closed(3, 0).scan(Heading.EAST, LIMITS);
        assertEquals(2, network.firstBranchLength(), "the rails up to the closed one");
        assertEquals(NetworkStop.CLOSED, network.stop());
        assertEquals(3, network.stopDx());
        assertEquals(0, network.stopDz());
        assertTrue(network.isComplete(), "a closed rail is not a chunk problem; the scan saw everything");
    }

    @Test
    void stopsAtAnotherDockAndSaysSo() {
        RailNetwork network = fixture().railsEast(1, 3).otherDock(4, 0).scan(Heading.EAST, LIMITS);
        assertEquals(3, network.firstBranchLength());
        assertEquals(NetworkStop.SECOND_DOCK, network.stop());
        assertEquals(4, network.stopDx());
    }

    @Test
    void stopsAtAnUnloadedChunkAndKeepsWhatItKnew() {
        RailNetwork network = fixture().railsEast(1, 2).unloaded(3, 0).scan(Heading.EAST, LIMITS);
        assertEquals(2, network.firstBranchLength());
        assertEquals(NetworkStop.UNLOADED, network.stop());
        assertTrue(network.reachedUnloadedChunk());
        assertFalse(network.isComplete());
        assertEquals(9, network.resolveFirstBranchLength(9, 32), "a partial scan never shrinks a warehouse");
        assertEquals(2, fixture().railsEast(1, 2).scan(Heading.EAST, LIMITS).resolveFirstBranchLength(9, 32),
                "but a complete one is authoritative");
    }

    @Test
    void refusesToEnterABranchingRailAndNamesIt() {
        // A T at (3, 0): the run east, and a spur north and south out of it.
        RailNetwork network = fixture().railsEast(1, 5).rail(3, 1).rail(3, -1).scan(Heading.EAST, LIMITS);
        assertEquals(1, network.branchCount());
        assertEquals(2, network.firstBranchLength(), "the chain stops one rail short of the junction");
        assertEquals(NetworkStop.BRANCHED, network.stop());
        assertEquals(3, network.stopDx(), "and names the rail that splits");
        assertEquals(0, network.stopDz());
    }

    @Test
    void refusesARingAtTheFirstRailThatSplits() {
        // A ring of eight rails whose near corner touches the dock: (1,0) then has three connections.
        Fixture ring = fixture().railsEast(1, 3).railsSouth(3, 1, 2).rail(2, 2).rail(1, 2).rail(1, 1);
        RailNetwork network = ring.scan(Heading.EAST, LIMITS);
        assertEquals(NetworkStop.BRANCHED, network.stop(), "a loop always has a rail with three connections");
        assertTrue(network.branchCount() >= 1, "and what is left is still a warehouse");
        assertTrue(network.geometry().rackPositionCount() > 0);
    }

    @Test
    void keepsAValidNetworkAtEveryCap() {
        Fixture fixture = fixture().railsEast(1, 6).railsSouth(6, 1, 4);

        RailNetwork rails = fixture.scan(Heading.EAST, new RailGraph.Limits(3, 26, 32, HEIGHT));
        assertEquals(3, rails.rails(), "the walk stops at the rail cap");
        assertEquals(NetworkStop.MAX_RAILS, rails.stop());
        assertEquals(1, rails.branchCount());

        RailNetwork branches = fixture.scan(Heading.EAST, new RailGraph.Limits(64, 1, 32, HEIGHT));
        assertEquals(1, branches.branchCount(), "maxBranches = 1 is the off switch: one straight aisle");
        assertEquals(6, branches.firstBranchLength(), "and the aisle it used to be is untouched");
        assertEquals(NetworkStop.END, branches.stop(), "which simply ends where its rails do, as it did in 0.5.0");

        RailNetwork length = fixture.scan(Heading.EAST, new RailGraph.Limits(64, 26, 4, HEIGHT));
        assertEquals(1, length.branchCount(), "a truncated branch takes the branches behind it with it");
        assertEquals(4, length.firstBranchLength(), "truncated at its far end, so nothing is renumbered");
        assertEquals(NetworkStop.MAX_LENGTH, length.stop());
        assertEquals(5, length.stopDx(), "naming the first rail that is left out");
    }

    /**
     * The bounded cost the design promises: a cap stops the <b>walk</b>, not only the result, so a warehouse whose
     * limits are reached never costs more to discover than the warehouse those limits allow. This is also what makes
     * {@code aisle.maxBranches = 1} cost exactly what every version before M21 cost.
     */
    @Test
    void aCapStopsTheWalkRatherThanTheResult() {
        Fixture line = fixture().railsEast(1, 60);
        int[] reads = new int[1];
        RailNetwork network = line.scanCounting(Heading.EAST, new RailGraph.Limits(64, 26, 4, HEIGHT), reads);
        assertEquals(4, network.firstBranchLength());
        assertEquals(NetworkStop.MAX_LENGTH, network.stop());
        assertTrue(reads[0] < 40, "the walk read " + reads[0] + " positions for a warehouse of four rails");

        Fixture bend = fixture().railsEast(1, 4).railsSouth(4, 1, 40);
        reads[0] = 0;
        RailNetwork oneBranch = bend.scanCounting(Heading.EAST, new RailGraph.Limits(64, 1, 32, HEIGHT), reads);
        assertEquals(4, oneBranch.firstBranchLength(), "the aisle at the dock, and nothing round the bend");
        assertEquals(NetworkStop.END, oneBranch.stop());
        assertTrue(reads[0] <= 5, "the off switch read " + reads[0] + " positions for an aisle of four rails");
    }

    /**
     * The off switch has to be <b>exact</b>, not merely similar: with {@code aisle.maxBranches = 1} discovery reads
     * nothing beside the dock's own line, so neither a decorative rail next to an aisle nor an unloaded chunk beside
     * it can change that aisle by a single block. Before this, the connection count was taken first and a stray rail
     * collapsed an eight-rail warehouse to one — with the off switch on (M21 review fix).
     */
    @Test
    void theOffSwitchReadsNothingBesideTheAisleLine() {
        RailGraph.Limits off = new RailGraph.Limits(64, 1, 32, HEIGHT);
        Fixture stray = fixture().railsEast(1, 8).rail(2, 1);
        RailNetwork network = stray.scan(Heading.EAST, off);
        assertEquals(8, network.firstBranchLength(), "a rail beside the aisle is a rack position, not a junction");
        assertEquals(NetworkStop.END, network.stop());
        assertTrue(network.isComplete());

        RailNetwork beside = fixture().railsEast(1, 8).unloaded(2, 1).scan(Heading.EAST, off);
        assertEquals(8, beside.firstBranchLength());
        assertTrue(beside.isComplete(), "and an unloaded block beside it was never even looked at");

        // With corners on, the same stray rail really is a junction the chain may not enter - that is the one stated
        // behaviour change of this milestone, and closing the rail is its cure.
        assertEquals(NetworkStop.BRANCHED, stray.scan(Heading.EAST, LIMITS).stop());
    }

    /**
     * An unloaded block <b>beside</b> an aisle says the network may be larger (a rail there would be a turn), but it
     * can never make that aisle longer — so the dock's own length stays authoritative and a warehouse whose rack plane
     * falls outside the loaded area can still shrink. Reading one flag for both questions froze such an aisle's length
     * for ever (M21 review fix).
     */
    @Test
    void anUnloadedBlockBesideAnAisleNeverFreezesItsLength() {
        RailNetwork beside = fixture().railsEast(1, 4).unloaded(2, 1).scan(Heading.EAST, LIMITS);
        assertEquals(4, beside.firstBranchLength());
        assertEquals(NetworkStop.END, beside.stop(), "the rails end where they end");
        assertTrue(beside.reachedUnloadedChunk(), "the network as a whole may still be larger");
        assertFalse(beside.isComplete());
        assertFalse(beside.firstBranchIncomplete(), "but the aisle at the dock was seen to its end");
        assertEquals(4, beside.resolveFirstBranchLength(9, 32), "so removing a rail still shortens the warehouse");

        RailNetwork ahead = fixture().railsEast(1, 4).unloaded(5, 0).scan(Heading.EAST, LIMITS);
        assertTrue(ahead.firstBranchIncomplete(), "a block the aisle could have continued into is another matter");
        assertEquals(9, ahead.resolveFirstBranchLength(9, 32), "and then the known length is kept");
    }

    @Test
    void discoversTheSameBuildFromEveryDockFacing() {
        for (Heading heading : Heading.values()) {
            Fixture fixture = fixture();
            // The L of cutsAnLIntoTwoBranchesSharingTheCorner, turned with the dock.
            for (int x = 1; x <= 4; x++)
                fixture.rail(turnX(heading, x, 0), turnZ(heading, x, 0));
            for (int z = 1; z <= 3; z++)
                fixture.rail(turnX(heading, 4, z), turnZ(heading, 4, z));

            RailNetwork network = fixture.scan(heading, LIMITS);
            assertEquals(2, network.branchCount(), "two branches facing " + heading);
            assertEquals(4, network.geometry().branch(0).length());
            assertEquals(heading, network.geometry().branch(0).heading());
            assertEquals(3, network.geometry().branch(1).length());
            assertEquals(heading.right(), network.geometry().branch(1).heading(), "the bend is always to the right");
            assertEquals(List.of(new BranchLink(0, 4, 1, 0)), network.geometry().links());
            assertEquals(NetworkStop.END, network.stop());
        }
    }

    // --- fixture ---------------------------------------------------------------------------------------------------

    /** Turns an east-relative offset onto {@code heading}, the way a rotated copy of a build sits in the world. */
    private static int turnX(Heading heading, int along, int right) {
        return along * heading.stepX() + right * heading.right().stepX();
    }

    private static int turnZ(Heading heading, int along, int right) {
        return along * heading.stepZ() + right * heading.right().stepZ();
    }

    private static Fixture fixture() {
        return new Fixture();
    }

    /** A hand-built world of aisle blocks around a dock at {@code (0, 0)}. */
    private static final class Fixture {
        private final Map<Long, RailGraph.Cell> cells = new HashMap<>();

        Fixture rail(int dx, int dz) {
            return put(dx, dz, RailGraph.Cell.RAIL);
        }

        Fixture closed(int dx, int dz) {
            return put(dx, dz, RailGraph.Cell.CLOSED_RAIL);
        }

        Fixture otherDock(int dx, int dz) {
            return put(dx, dz, RailGraph.Cell.OTHER_DOCK);
        }

        Fixture unloaded(int dx, int dz) {
            return put(dx, dz, RailGraph.Cell.UNLOADED);
        }

        /** Rails at {@code (from..to, 0)}. */
        Fixture railsEast(int from, int to) {
            for (int dx = from; dx <= to; dx++)
                rail(dx, 0);
            return this;
        }

        /** Rails at {@code (dx, from..to)}. */
        Fixture railsSouth(int dx, int from, int to) {
            for (int dz = from; dz <= to; dz++)
                rail(dx, dz);
            return this;
        }

        /** Rails at {@code (fromDx + 1 .. fromDx + count, dz)}. */
        Fixture railsEastFrom(int fromDx, int dz, int count) {
            for (int step = 1; step <= count; step++)
                rail(fromDx + step, dz);
            return this;
        }

        RailNetwork scan(Heading dockHeading, RailGraph.Limits limits) {
            return RailGraph.scan(this::at, dockHeading, limits);
        }

        /** The same scan, counting the <b>distinct</b> positions it read, which is what the cost is bounded in. */
        RailNetwork scanCounting(Heading dockHeading, RailGraph.Limits limits, int[] reads) {
            Set<Long> seen = new HashSet<>();
            RailNetwork network = RailGraph.scan((dx, dz) -> {
                seen.add(RailGraph.cell(dx, dz));
                return at(dx, dz);
            }, dockHeading, limits);
            reads[0] = seen.size();
            return network;
        }

        private RailGraph.Cell at(int dx, int dz) {
            return dx == 0 && dz == 0 ? RailGraph.Cell.DOCK
                    : cells.getOrDefault(RailGraph.cell(dx, dz), RailGraph.Cell.NONE);
        }

        private Fixture put(int dx, int dz, RailGraph.Cell cell) {
            cells.put(RailGraph.cell(dx, dz), cell);
            return this;
        }
    }
}
