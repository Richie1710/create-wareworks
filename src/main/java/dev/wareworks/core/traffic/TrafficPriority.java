package dev.wareworks.core.traffic;

import java.util.Comparator;

/**
 * The order in which the traffic ledger serves requests: a total order with no hashing in it, so the same cranes in
 * any order are always served the same way (issue #14, ADR-075 §5.2).
 * <ol>
 * <li><b>Yield orders</b> first, with the age of the request they serve — priority inheritance, so a crane moving
 * aside is never held up by the cranes it moves aside for;</li>
 * <li>then <b>job wants</b>, the oldest first, then the lower crane number;</li>
 * <li>then <b>rest wants</b> (a crane driving home with nothing to do), by crane number.</li>
 * </ol>
 *
 * @param rank  {@value #YIELD}, {@value #JOB} or {@value #REST}
 * @param since the game tick the request was first posted (for a yield: that of the request it serves); 0 for rest
 * @param crane the crane number, the deterministic tie-break
 */
public record TrafficPriority(int rank, long since, int crane) implements Comparable<TrafficPriority> {
    /** Rank of a yield order. */
    public static final int YIELD = 0;
    /** Rank of a job want. */
    public static final int JOB = 1;
    /** Rank of a rest want. */
    public static final int REST = 2;

    private static final Comparator<TrafficPriority> ORDER = Comparator.comparingInt(TrafficPriority::rank)
            .thenComparingLong(TrafficPriority::since)
            .thenComparingInt(TrafficPriority::crane);

    public static TrafficPriority job(long since, int crane) {
        return new TrafficPriority(JOB, since, crane);
    }

    public static TrafficPriority rest(int crane) {
        return new TrafficPriority(REST, 0L, crane);
    }

    /** A yield order of {@code crane}, inheriting the age of the request it serves. */
    public static TrafficPriority yieldFor(long servedSince, int crane) {
        return new TrafficPriority(YIELD, servedSince, crane);
    }

    @Override
    public int compareTo(TrafficPriority other) {
        return ORDER.compare(this, other);
    }
}
