package dev.wareworks.core.traffic;

import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import dev.wareworks.core.address.RackPosition;

/**
 * Which crane of a warehouse takes a job: the warehouse decides, and it gives the job to the <b>nearest free
 * crane</b> (issue #14, ADR-075 §8).
 * <p>
 * "Nearest" is the approach time to the job's source at <b>that crane's own speeds</b> — every dock has its own
 * rotation — then the fewer blocks on the way, then the lower crane number, so the choice is deterministic. Only cranes
 * {@link Eligibility eligible} for both the source and the target take part. With a single crane there is one offer,
 * and the choice is that crane whenever it may do the job at all — exactly what a warehouse of one crane always did.
 */
public final class CraneChoice {
    /** Lowest approach time, then fewest blocks, then lowest crane number. */
    public static final Comparator<Offer> NEAREST = Comparator.comparingLong(Offer::approachTicks)
            .thenComparingDouble(Offer::approachBlocks)
            .thenComparingInt(Offer::crane);

    private CraneChoice() {
    }

    /**
     * One free crane's offer for a job.
     *
     * @param crane          the crane number
     * @param eligible       whether it may do the job: both its source and its target are {@link Eligibility eligible}
     *                       for it
     * @param approachTicks  ticks it takes from where it stands to the source, at its own speeds
     * @param approachBlocks blocks of that approach, the first tie-break
     */
    public record Offer(int crane, boolean eligible, long approachTicks, double approachBlocks) {
        public Offer {
            if (crane < 1)
                throw new IllegalArgumentException("crane numbers start at 1: " + crane);
            if (approachTicks < 0)
                throw new IllegalArgumentException("approachTicks must not be negative: " + approachTicks);
            if (!(approachBlocks >= 0.0))
                throw new IllegalArgumentException("approachBlocks must not be negative: " + approachBlocks);
        }
    }

    /** The crane that takes the job, or empty when no free crane may do it. */
    public static OptionalInt choose(List<Offer> offers) {
        Objects.requireNonNull(offers, "offers");
        return offers.stream()
                .filter(Offer::eligible)
                .min(NEAREST)
                .map(offer -> OptionalInt.of(offer.crane()))
                .orElse(OptionalInt.empty());
    }

    /**
     * Which rack positions one crane may serve (ADR-075 §8.2), on top of what every crane is asked (loaded, and
     * reachable from its own point):
     * <ul>
     * <li>its <b>level</b> is below this crane's own mast — each dock keeps its own mast height, so a lower member
     * serves fewer levels;</li>
     * <li>the block it is served from is <b>not another crane's dock</b>: the positions beside a dock are that dock's
     * own crane's, as the positions beside the warehouse's own dock always were. No crane is ever sent to stand on
     * another crane's dock, which is one of the assumptions the deadlock argument needs;</li>
     * <li>it is reachable <b>without passing a busy crane</b>: a crane with a job finishes it, and a free crane is never
     * given work behind it. Idle cranes are no obstacle here, because they move aside.</li>
     * </ul>
     */
    public static final class Eligibility {
        private final TrackGraph graph;
        private final int mastHeight;
        private final BitSet otherDocks;
        private final BitSet reachable;

        private Eligibility(TrackGraph graph, int mastHeight, BitSet otherDocks, BitSet reachable) {
            this.graph = graph;
            this.mastHeight = mastHeight;
            this.otherDocks = otherDocks;
            this.reachable = reachable;
        }

        /**
         * The eligibility of one crane.
         *
         * @param standing    the cells the crane stands on
         * @param mastHeight  its own mast height in levels
         * @param ownDock     its own dock cell
         * @param docks       every dock cell of the warehouse, its own included
         * @param busySettled the settled cells of every <b>other</b> crane that has a job
         */
        public static Eligibility of(TrackGraph graph, int[] standing, int mastHeight, int ownDock, int[] docks,
                List<int[]> busySettled) {
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(standing, "standing");
            Objects.requireNonNull(docks, "docks");
            Objects.requireNonNull(busySettled, "busySettled");
            BitSet otherDocks = new BitSet();
            for (int dock : docks) {
                if (dock >= 0 && dock != ownDock)
                    otherDocks.set(dock);
            }
            BitSet blocked = new BitSet();
            for (int[] cells : busySettled) {
                for (int cell : cells) {
                    if (cell >= 0)
                        blocked.set(cell);
                }
            }
            BitSet reachable = graph.reachable(standing, blocked);
            for (int cell : standing)
                reachable.set(cell);
            return new Eligibility(graph, mastHeight, otherDocks, reachable);
        }

        /** Whether this crane may serve {@code rack}. */
        public boolean allows(RackPosition rack) {
            Objects.requireNonNull(rack, "rack");
            if (rack.y() >= mastHeight)
                return false;
            int cell = graph.cellAt(rack.branch(), rack.x());
            return cell != TrackGraph.NONE && !otherDocks.get(cell) && reachable.get(cell);
        }

        /** Whether this crane may stand on {@code cell} for a job. */
        public boolean allowsCell(int cell) {
            return cell >= 0 && !otherDocks.get(cell) && reachable.get(cell);
        }
    }
}
