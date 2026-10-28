package dev.wareworks.core.traffic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.warehouse.RouteTable;

/**
 * Which home point each crane of a warehouse parks at (issue #14, ADR-075 §8.4). Home points only say <b>where</b> a
 * crane waits; which crane does what is the warehouse's decision ({@link CraneChoice}).
 * <p>
 * Every (crane, home point) pair the rails join is ranked by the route blocks from the crane's dock to the home point,
 * then the crane number, then {@link RackPosition#ORDER}, and the pairs are taken greedily: each crane gets at most
 * one home point and each home point at most one crane. A home point on another crane's dock block is never paired
 * with a crane — no crane may ever be sent to stand there. What is left over is reported, so the block can say that
 * the warehouse has more home points than cranes. Deterministic, and event driven: the controller asks again whenever
 * the home points or the cranes change, never per tick.
 */
public final class HomeAssignment {
    private HomeAssignment() {
    }

    /**
     * One crane and its dock.
     *
     * @param crane  the crane number
     * @param branch the branch its dock lies on
     * @param x      its dock's position on that branch
     */
    public record Dock(int crane, int branch, int x) {
    }

    /**
     * The pairing.
     *
     * @param homes      the home point of each crane that got one, by crane number
     * @param unassigned the home points no crane parks at, in {@link RackPosition#ORDER}
     */
    public record Result(Map<Integer, RackPosition> homes, List<RackPosition> unassigned) {
        public Result {
            homes = Map.copyOf(homes);
            unassigned = List.copyOf(unassigned);
        }
    }

    private record Pair(Dock dock, RackPosition home, double blocks) {
    }

    private static final Comparator<Pair> ORDER = Comparator.comparingDouble(Pair::blocks)
            .thenComparingInt(pair -> pair.dock().crane())
            .thenComparing(Pair::home, RackPosition.ORDER);

    /**
     * Pairs {@code docks} with {@code homePoints}.
     *
     * @param turnPenaltyBlocks the price of a quarter turn the route blocks are measured with
     */
    public static Result assign(List<Dock> docks, List<RackPosition> homePoints, RouteTable routes,
            double turnPenaltyBlocks) {
        Objects.requireNonNull(docks, "docks");
        Objects.requireNonNull(homePoints, "homePoints");
        Objects.requireNonNull(routes, "routes");
        Set<Long> dockBlocks = new HashSet<>();
        for (Dock dock : docks)
            dockBlocks.add(key(dock.branch(), dock.x()));
        List<Pair> pairs = new ArrayList<>();
        for (Dock dock : docks) {
            for (RackPosition home : homePoints) {
                boolean onOwnDock = home.branch() == dock.branch() && home.x() == dock.x();
                if (!onOwnDock && dockBlocks.contains(key(home.branch(), home.x())))
                    continue;
                OptionalDouble blocks = routes.routeBlocks(dock.branch(), dock.x(), home.branch(), home.x(),
                        turnPenaltyBlocks);
                if (blocks.isPresent())
                    pairs.add(new Pair(dock, home, blocks.getAsDouble()));
            }
        }
        pairs.sort(ORDER);
        Map<Integer, RackPosition> homes = new TreeMap<>();
        Set<RackPosition> taken = new HashSet<>();
        for (Pair pair : pairs) {
            if (homes.containsKey(pair.dock().crane()) || taken.contains(pair.home()))
                continue;
            homes.put(pair.dock().crane(), pair.home());
            taken.add(pair.home());
        }
        List<RackPosition> unassigned = new ArrayList<>();
        for (RackPosition home : homePoints) {
            if (!taken.contains(home) && !unassigned.contains(home))
                unassigned.add(home);
        }
        unassigned.sort(RackPosition.ORDER);
        return new Result(homes, unassigned);
    }

    private static long key(int branch, int x) {
        return ((long) branch << 32) | (x & 0xFFFFFFFFL);
    }
}
