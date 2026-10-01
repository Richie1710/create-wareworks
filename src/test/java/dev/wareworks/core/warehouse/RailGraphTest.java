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
    /** More junctions than any shape here has, so {@code aisle.maxJunctions} is never what a test is about. */
    private static final int MANY_JUNCTIONS = 64;
    private static final RailGraph.Limits LIMITS = new RailGraph.Limits(64, 26, MANY_JUNCTIONS, 32, HEIGHT);

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

    /**
     * A T (M22, issue #2): the rails split and <b>all</b> of them are one warehouse. The run keeps its one letter
     * because two collinear touching rails are always the same branch, and the aisle crossing it is one aisle on both
     * sides of the run, numbered from the end nearer the dock.
     */
    @Test
    void followsATeeAsTwoAisles() {
        // A T at (3, 0): the run east, and a spur north and south out of it.
        RailNetwork network = fixture().railsEast(1, 5).rail(3, 1).rail(3, -1).scan(Heading.EAST, LIMITS);
        assertEquals(2, network.branchCount());
        assertEquals(5, network.firstBranchLength(), "the run goes straight through the junction, in one piece");
        assertEquals(new BranchGeometry(0, 0, 0, Heading.EAST, 5), network.geometry().branch(0));
        assertEquals(new BranchGeometry(1, 3, -1, Heading.SOUTH, 2), network.geometry().branch(1),
                "and the crossing aisle is one aisle, from the end nearer the dock");
        assertEquals(List.of(new BranchLink(0, 3, 1, 1)), network.geometry().links(),
                "they share the junction block, which has a legal name on both");
        assertEquals(7, network.rails(), "every rail belongs to an aisle, and the junction is counted once");
        assertEquals(NetworkStop.END, network.stop(), "nothing is refused any more");
        assertTrue(network.isComplete());
    }

    /**
     * A ring. The shape M21 reported as "the rails lead back into themselves" is a warehouse whose aisles meet twice,
     * so there are two ways round and {@link RouteCosts} decides which one a job drives.
     */
    @Test
    void followsARingAsOneWarehouse() {
        // A ring of eight rails whose near corner touches the dock.
        Fixture ring = fixture().railsEast(1, 3).railsSouth(3, 1, 2).rail(2, 2).rail(1, 2).rail(1, 1);
        RailNetwork network = ring.scan(Heading.EAST, LIMITS);
        assertEquals(NetworkStop.END, network.stop(), "a ring is a warehouse, not a refusal");
        assertTrue(network.isComplete());
        assertEquals(4, network.branchCount(), "four straight runs");
        assertEquals(8, network.rails(), "every rail of the ring, each counted once");
        assertEquals(4, network.geometry().links().size(), "meeting at four junctions");
        // Two ways round, and both of them really exist: the planner picks by cost, never by which it found first.
        RouteCosts costs = RouteCosts.of(network.geometry(), 0.0);
        assertTrue(costs.reachable(0, 2), "the far side of the ring is reachable");
        assertEquals(costs.componentOf(0), costs.componentOf(2),
                "and it is the same warehouse as the aisle at the dock");
    }

    /**
     * A cross. Four rails meet, and the two aisles through that block are still <b>two</b> aisles: one per axis, each
     * in one piece, because a maximal straight run is what a branch is.
     */
    @Test
    void followsACrossAsTwoAisles() {
        RailNetwork network = fixture().railsEast(1, 4).rail(2, 1).rail(2, -1).scan(Heading.EAST, LIMITS);
        assertEquals(2, network.branchCount());
        assertEquals(new BranchGeometry(0, 0, 0, Heading.EAST, 4), network.geometry().branch(0));
        assertEquals(new BranchGeometry(1, 2, -1, Heading.SOUTH, 2), network.geometry().branch(1));
        assertEquals(List.of(new BranchLink(0, 2, 1, 1)), network.geometry().links());
        assertEquals(NetworkStop.END, network.stop());
    }

    /**
     * A comb — one main run with three side aisles — is the second tested shape of M22 beside the L, and the one the
     * milestone exists for: goods from every side aisle reach one block. The main run keeps <b>one</b> letter however
     * many teeth hang off it, and each tooth is numbered from the junction outwards, so extending a tooth renumbers
     * nothing.
     */
    @Test
    void followsACombWithOneLetterForItsMainRun() {
        Fixture comb = fixture().railsEast(1, 12);
        for (int tooth : new int[] { 3, 7, 11 })
            comb.railsSouth(tooth, 1, 3);
        RailNetwork network = comb.scan(Heading.EAST, LIMITS);
        assertEquals(NetworkStop.END, network.stop());
        assertEquals(4, network.branchCount(), "the main run and one aisle per tooth");
        assertEquals(new BranchGeometry(0, 0, 0, Heading.EAST, 12), network.geometry().branch(0),
                "the main run is one aisle with one letter");
        assertEquals(new BranchGeometry(1, 3, 0, Heading.SOUTH, 3), network.geometry().branch(1));
        assertEquals(new BranchGeometry(2, 7, 0, Heading.SOUTH, 3), network.geometry().branch(2),
                "the teeth come in the order the rails reach them from the dock");
        assertEquals(new BranchGeometry(3, 11, 0, Heading.SOUTH, 3), network.geometry().branch(3));
        assertEquals(List.of(new BranchLink(0, 3, 1, 0), new BranchLink(0, 7, 2, 0), new BranchLink(0, 11, 3, 0)),
                network.geometry().links(), "each tooth joins the run at its own junction");
        assertEquals(21, network.rails(), "twelve on the run and three per tooth, the junctions counted once");
    }

    @Test
    void keepsAValidNetworkAtEveryCap() {
        Fixture fixture = fixture().railsEast(1, 6).railsSouth(6, 1, 4);

        RailNetwork rails = fixture.scan(Heading.EAST, new RailGraph.Limits(3, 26, MANY_JUNCTIONS, 32, HEIGHT));
        assertEquals(3, rails.rails(), "the walk stops at the rail cap");
        assertEquals(NetworkStop.MAX_RAILS, rails.stop());
        assertEquals(1, rails.branchCount());

        RailNetwork branches = fixture.scan(Heading.EAST, new RailGraph.Limits(64, 1, MANY_JUNCTIONS, 32, HEIGHT));
        assertEquals(1, branches.branchCount(), "maxBranches = 1 is the off switch: one straight aisle");
        assertEquals(6, branches.firstBranchLength(), "and the aisle it used to be is untouched");
        assertEquals(NetworkStop.END, branches.stop(), "which simply ends where its rails do, as it did in 0.5.0");

        RailNetwork length = fixture.scan(Heading.EAST, new RailGraph.Limits(64, 26, MANY_JUNCTIONS, 4, HEIGHT));
        assertEquals(4, length.firstBranchLength(), "truncated at its far end, so nothing is renumbered");
        assertEquals(NetworkStop.MAX_LENGTH, length.stop());
        assertEquals(5, length.stopDx(), "naming the first rail that is left out");
        // Since M22 the aisle beyond the cut is KEPT rather than deleted (issue #2): the cut took away the junction it
        // joined the warehouse at, so it is an aisle the crane cannot reach - which the warehouse reports and the
        // planner refuses jobs towards. Dropping it would take a player's chests out of the address space because a
        // number in a config file is too small.
        assertEquals(2, length.branchCount(), "the aisle beyond the cut is still part of the warehouse");
        RouteCosts costs = RouteCosts.of(length.geometry(), 0.0);
        assertFalse(costs.reachable(0, 1), "but the crane cannot get to it any more");
        assertTrue(costs.costBlocks(0, 0.0, 1, 0.0).isEmpty(), "so no trip there has a cost");
        RailNetwork raised = fixture.scan(Heading.EAST, new RailGraph.Limits(64, 26, MANY_JUNCTIONS, 6, HEIGHT));
        assertEquals(6, raised.firstBranchLength(), "and raising the number brings the junction back");
        assertTrue(RouteCosts.of(raised.geometry(), 0.0).reachable(0, 1), "which joins the aisle again");
    }

    /**
     * {@code aisle.maxJunctions} is the bound on what a route search costs, so it is the one cap that counts
     * <b>junctions</b> rather than rails or aisles. A comb over it keeps the teeth it can afford, in the deterministic
     * branch order, and names the key to raise.
     */
    @Test
    void keepsTheNearTeethOfACombAtTheJunctionCap() {
        Fixture comb = fixture().railsEast(1, 12);
        for (int tooth : new int[] { 3, 7, 11 })
            comb.railsSouth(tooth, 1, 3);

        RailNetwork two = comb.scan(Heading.EAST, new RailGraph.Limits(64, 26, 2, 32, HEIGHT));
        assertEquals(3, two.branchCount(), "the main run and the two teeth nearest the dock");
        assertEquals(NetworkStop.MAX_JUNCTIONS, two.stop());
        assertEquals(11, two.stopDx(), "naming the tooth that was left out");
        assertEquals(1, two.stopDz());

        RailNetwork none = comb.scan(Heading.EAST, new RailGraph.Limits(64, 26, 0, 32, HEIGHT));
        assertEquals(1, none.branchCount(), "0 keeps the aisle at the dock and nothing that joins it");
        assertEquals(12, none.firstBranchLength(), "which is the whole main run");
        assertEquals(NetworkStop.MAX_JUNCTIONS, none.stop());
    }

    /**
     * {@code aisle.maxBranches} keeps a <b>prefix</b> of the branch order, and a prefix is still a connected
     * warehouse: a tooth is reached through the run, whose nearest block is one step closer to the dock, so a
     * connector always sorts before what it connects.
     */
    @Test
    void keepsAConnectedPrefixAtTheAisleCap() {
        Fixture comb = fixture().railsEast(1, 12);
        for (int tooth : new int[] { 3, 7, 11 })
            comb.railsSouth(tooth, 1, 3);

        RailNetwork capped = comb.scan(Heading.EAST, new RailGraph.Limits(64, 3, MANY_JUNCTIONS, 32, HEIGHT));
        assertEquals(3, capped.branchCount());
        assertEquals(NetworkStop.MAX_BRANCHES, capped.stop());
        assertEquals(11, capped.stopDx(), "naming the first rail of the aisle that was left out");
        RouteCosts costs = RouteCosts.of(capped.geometry(), 0.0);
        for (int branch = 1; branch < capped.branchCount(); branch++)
            assertTrue(costs.reachable(0, branch), "every aisle it kept is reachable (" + branch + ")");
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
        // aisle.maxNetworkRails is the key this is bounded in, and the only one that CAN bound it: since M22 the scan
        // is a flood, so which straight run a block belongs to - and therefore whether aisle.maxAisleLength bites - is
        // not known until the whole network has been taken. The flood stops at the rail cap, before the block itself.
        RailNetwork network = line.scanCounting(Heading.EAST, new RailGraph.Limits(8, 26, MANY_JUNCTIONS, 32, HEIGHT),
                reads);
        assertEquals(8, network.firstBranchLength());
        assertEquals(NetworkStop.MAX_RAILS, network.stop());
        assertTrue(reads[0] < 40, "the scan read " + reads[0] + " positions for a warehouse of eight rails");
        assertEquals(4, line.scan(Heading.EAST, new RailGraph.Limits(64, 26, MANY_JUNCTIONS, 4, HEIGHT))
                .firstBranchLength(), "and the length cap still truncates the result at its far end");

        Fixture bend = fixture().railsEast(1, 4).railsSouth(4, 1, 40);
        reads[0] = 0;
        RailNetwork oneBranch = bend.scanCounting(Heading.EAST, new RailGraph.Limits(64, 1, MANY_JUNCTIONS, 32, HEIGHT), reads);
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
        RailGraph.Limits off = new RailGraph.Limits(64, 1, MANY_JUNCTIONS, 32, HEIGHT);
        Fixture stray = fixture().railsEast(1, 8).rail(2, 1);
        RailNetwork network = stray.scan(Heading.EAST, off);
        assertEquals(8, network.firstBranchLength(), "a rail beside the aisle is a rack position, not a junction");
        assertEquals(NetworkStop.END, network.stop());
        assertTrue(network.isComplete());

        RailNetwork beside = fixture().railsEast(1, 8).unloaded(2, 1).scan(Heading.EAST, off);
        assertEquals(8, beside.firstBranchLength());
        assertTrue(beside.isComplete(), "and an unloaded block beside it was never even looked at");

        // With corners on, the same stray rail is a one-block side aisle of the warehouse - the stated behaviour
        // change of M21, and since M22 it no longer stops the aisle it hangs off either. Closing it is still the cure.
        RailNetwork joined = stray.scan(Heading.EAST, LIMITS);
        assertEquals(NetworkStop.END, joined.stop());
        assertEquals(8, joined.firstBranchLength(), "the aisle runs past the stray rail, not up to it");
        assertEquals(2, joined.branchCount(), "which has joined as an aisle of one rail");
        assertEquals(new BranchGeometry(1, 2, 0, Heading.SOUTH, 1), joined.geometry().branch(1));
    }

    /**
     * An unloaded block <b>beside</b> an aisle says the network may be larger (a rail there would be another aisle),
     * but it can never make that aisle longer — so the dock's own length stays authoritative and a warehouse whose
     * rack plane falls outside the loaded area can still shrink. Reading one flag for both questions froze such an
     * aisle's length for ever (M21 review fix).
     * <p>
     * Since M22 the scan floods rather than following a chain, so such a block is a place the <b>network</b> could
     * continue and is named as the stop — which is what {@code isComplete} already said about it. The two flags are
     * unchanged, and they are what the length rule reads.
     */
    @Test
    void anUnloadedBlockBesideAnAisleNeverFreezesItsLength() {
        RailNetwork beside = fixture().railsEast(1, 4).unloaded(2, 1).scan(Heading.EAST, LIMITS);
        assertEquals(4, beside.firstBranchLength());
        assertEquals(NetworkStop.UNLOADED, beside.stop(), "a block an aisle could start at is named");
        assertEquals(2, beside.stopDx());
        assertEquals(1, beside.stopDz());
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
