package dev.wareworks.core.traffic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.warehouse.RailGraph;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.core.warehouse.RouteTable;

/**
 * Hand-built warehouses with several docks for the traffic tests (ADR-075 §7). Every shape is discovered by the real
 * {@link RailGraph} from a fixture of rails and free docks, so the tests also check that discovery and the track
 * graph agree. The warehouse's own dock always stands at {@code (0, 0)} facing east.
 */
final class TestNetworks {
    static final int HEIGHT = 4;
    /** Generous bounds: no maximum is ever what a traffic test is about. */
    static final RailGraph.Limits LIMITS = new RailGraph.Limits(1024, 26, 64, 128, HEIGHT, 16);

    private TestNetworks() {
    }

    /** A discovered shape with everything a traffic test needs. */
    record Shape(String name, RailNetwork network, TrackGraph graph, RouteTable routes) {
        /** The cell of the aisle block at the dock-relative offset {@code (dx, dz)}. */
        int cell(int dx, int dz) {
            for (BranchGeometry branch : network.geometry().branches()) {
                Optional<Integer> x = branch.positionAt(dx, dz);
                if (x.isPresent())
                    return graph.cellAt(branch.index(), x.get());
            }
            throw new IllegalArgumentException(name + " has no aisle block at " + dx + ", " + dz);
        }

        /** Cranes this shape has: one per dock. */
        int cranes() {
            return graph.docks().length;
        }

        /** The dock cell of crane {@code number}. */
        int dock(int number) {
            return graph.docks()[number - 1];
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A world of rails and free docks around the warehouse's own dock. */
    static final class Builder {
        private final Map<Long, RailGraph.Cell> cells = new HashMap<>();
        private final Map<Long, Heading> facings = new HashMap<>();

        Builder rail(int dx, int dz) {
            cells.put(RailGraph.cell(dx, dz), RailGraph.Cell.RAIL);
            return this;
        }

        /** Rails at {@code (from..to, dz)}. */
        Builder railsX(int dz, int from, int to) {
            for (int dx = from; dx <= to; dx++)
                rail(dx, dz);
            return this;
        }

        /** Rails at {@code (dx, from..to)}. */
        Builder railsZ(int dx, int from, int to) {
            for (int dz = Math.min(from, to); dz <= Math.max(from, to); dz++)
                rail(dx, dz);
            return this;
        }

        /** A dock without a controller of its own at {@code (dx, dz)}, facing {@code facing}. */
        Builder dock(int dx, int dz, Heading facing) {
            cells.put(RailGraph.cell(dx, dz), RailGraph.Cell.FREE_DOCK);
            facings.put(RailGraph.cell(dx, dz), facing);
            return this;
        }

        RailNetwork scan(RailGraph.Limits limits) {
            return RailGraph.scan(new RailGraph.CellProbe() {
                @Override
                public RailGraph.Cell at(int dx, int dz) {
                    return dx == 0 && dz == 0 ? RailGraph.Cell.DOCK
                            : cells.getOrDefault(RailGraph.cell(dx, dz), RailGraph.Cell.NONE);
                }

                @Override
                public Heading dockFacing(int dx, int dz) {
                    return facings.get(RailGraph.cell(dx, dz));
                }
            }, Heading.EAST, limits);
        }

        Shape build(String name) {
            RailNetwork network = scan(LIMITS);
            TrackGraph graph = TrackGraph.of(network.geometry(), network.memberDocks());
            return new Shape(name, network, graph, RouteTable.of(network.geometry()));
        }
    }

    static Builder builder() {
        return new Builder();
    }

    /** A straight aisle of {@code rails} rails with a dock at each end. */
    static Shape line(int rails) {
        return builder().railsX(0, 1, rails).dock(rails + 1, 0, Heading.WEST).build("line" + rails);
    }

    /** A straight aisle with both docks at its west end: the second on a one-block stub beside position 1. */
    static Shape lineSameEnd(int rails) {
        return builder().railsX(0, 1, rails).dock(1, 1, Heading.NORTH).build("line-same-end" + rails);
    }

    /** A main run with a stem south off position 3: docks at the west end, the east end and the stem's end. */
    static Shape tee() {
        return builder().railsX(0, 1, 6).railsZ(3, 1, 3).dock(3, 4, Heading.NORTH).dock(7, 0, Heading.WEST)
                .build("tee");
    }

    /** A main run crossed at position 3: docks at all four ends. */
    static Shape cross() {
        return builder().railsX(0, 1, 6).railsZ(3, -3, 3).dock(7, 0, Heading.WEST).dock(3, 4, Heading.NORTH)
                .dock(3, -4, Heading.SOUTH).build("cross");
    }

    /** A square loop: the warehouse's own dock on its top row, two more docks on stubs. */
    static Shape ring() {
        return builder().railsX(0, 1, 4).railsX(3, 1, 4).railsZ(1, 1, 2).railsZ(4, 1, 2)
                .dock(5, 3, Heading.WEST).dock(2, 4, Heading.NORTH).build("ring");
    }

    /** A main run of 12 with three teeth of three south: docks at both ends of the main run and one tooth. */
    static Shape comb() {
        return builder().railsX(0, 1, 12).railsZ(3, 1, 3).railsZ(6, 1, 3).railsZ(9, 1, 3)
                .dock(13, 0, Heading.WEST).dock(6, 4, Heading.NORTH).build("comb");
    }

    /** Two rings sharing a rung: rows at z = 0 and z = 3, rungs at x = 1, 4 and 7. */
    static Shape ladder() {
        return builder().railsX(0, 1, 7).railsX(3, 1, 7).railsZ(1, 1, 2).railsZ(4, 1, 2).railsZ(7, 1, 2)
                .dock(8, 3, Heading.WEST).dock(8, 0, Heading.WEST).build("ladder");
    }

    /** A tree: a main run with a branch north and one south, docks at the ends of both branches. */
    static Shape deadEnds() {
        return builder().railsX(0, 1, 6).railsZ(2, -3, -1).railsZ(4, 1, 3).dock(2, -4, Heading.SOUTH)
                .dock(4, 4, Heading.NORTH).build("dead-ends");
    }

    /** The tee of the design discussion: a short main run and a stem whose end is the second crane's dock. */
    static Shape crossedTee() {
        return builder().railsX(0, 1, 4).railsZ(2, 1, 3).dock(2, 4, Heading.NORTH).build("crossed-tee");
    }

    /** Every shape the property tests run on. */
    static List<Shape> all() {
        return List.of(line(8), lineSameEnd(6), tee(), cross(), ring(), comb(), ladder(), deadEnds(), crossedTee());
    }

    // --- the tiny shapes of the exhaustive check ---------------------------------------------------------------

    /** Six cells: two docks and four rails between them. */
    static Shape tinyLine() {
        return builder().railsX(0, 1, 4).dock(5, 0, Heading.WEST).build("tiny-line");
    }

    /** Eight cells: a main run of three and a stem of two, a dock at the end of each. */
    static Shape tinyTee() {
        return builder().railsX(0, 1, 3).railsZ(2, 1, 2).dock(2, 3, Heading.NORTH).dock(4, 0, Heading.WEST)
                .build("tiny-tee");
    }

    /** A loop of eight rails with three docks. */
    static Shape tinyRing() {
        return builder().railsX(0, 1, 3).railsX(2, 1, 3).rail(1, 1).rail(3, 1).dock(4, 2, Heading.WEST)
                .dock(2, 3, Heading.NORTH).build("tiny-ring");
    }

    /** A loop of four rails (a square of two by two) with three docks: the smallest ring three cranes share. */
    static Shape tinySquare() {
        return builder().railsX(0, 1, 2).railsX(1, 1, 2).dock(3, 1, Heading.WEST).dock(1, 2, Heading.NORTH)
                .build("tiny-square");
    }

    /** Nine cells: a main run of four with two teeth of one, a dock at each end and one at the end of a tooth. */
    static Shape tinyComb3() {
        return builder().railsX(0, 1, 4).rail(1, 1).rail(3, 1).dock(5, 0, Heading.WEST).dock(3, 2, Heading.NORTH)
                .build("tiny-comb3");
    }

    /** Eleven cells: a main run of five with two short teeth, a dock at each end and one in a tooth. */
    static Shape tinyComb() {
        return builder().railsX(0, 1, 5).rail(2, 1).railsZ(4, 1, 2).dock(6, 0, Heading.WEST).dock(4, 3, Heading.NORTH)
                .build("tiny-comb");
    }
}
