package dev.wareworks.core.crane;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What all the cranes of one warehouse got done in the minute behind them together (issue #14, ADR-075 §15.3): the
 * number "Crane Throughput" shows when it is read off the controller instead of one dock. This is the sum ADR-039 said
 * the milestone with several cranes would need.
 * <p>
 * Trips and items are <b>summed</b>: that is what the warehouse moved. The busy and turning shares are the <b>mean</b>
 * over the cranes, rounded half up, because a share above 100 % says nothing: four cranes each busy half the minute are a
 * warehouse busy half the minute, with room for twice the work.
 * <p>
 * Only whole minutes are added up. While a single crane's window is not yet a full minute there is no total at all, and
 * the source says "Measuring" — the same rule as for one crane, for the same reason: a board cannot carry the caveat
 * "of the last 23 s", and a sum over windows of different lengths would be a number about nothing in particular.
 *
 * @param cranes     how many cranes are in the sum
 * @param trips      finished trips of all of them
 * @param items      items all of them delivered
 * @param busyShare  the mean busy share, in percent
 * @param turnShare  the mean turning share, in percent
 */
public record WarehouseThroughput(int cranes, long trips, long items, int busyShare, int turnShare) {
    public WarehouseThroughput {
        if (cranes < 1)
            throw new IllegalArgumentException("a total needs at least one crane: " + cranes);
        if (trips < 0 || items < 0)
            throw new IllegalArgumentException("counts must not be negative: " + trips + ", " + items);
        if (busyShare < 0 || busyShare > CraneThroughput.FULL_SHARE || turnShare < 0
                || turnShare > CraneThroughput.FULL_SHARE)
            throw new IllegalArgumentException("shares are percent: " + busyShare + ", " + turnShare);
    }

    /**
     * The total of {@code windows}, one per crane; empty for no crane at all and while any window is not yet a whole
     * minute.
     */
    public static Optional<WarehouseThroughput> of(List<CraneThroughput> windows) {
        Objects.requireNonNull(windows, "windows");
        if (windows.isEmpty())
            return Optional.empty();
        long trips = 0L;
        long items = 0L;
        long busy = 0L;
        long turning = 0L;
        for (CraneThroughput window : windows) {
            Objects.requireNonNull(window, "window");
            if (!window.isFullMinute())
                return Optional.empty();
            trips += window.trips();
            items += window.items();
            busy += window.busyShare();
            turning += window.turnShare();
        }
        int count = windows.size();
        return Optional.of(new WarehouseThroughput(count, trips, items, meanShare(busy, count),
                meanShare(turning, count)));
    }

    /** The mean of {@code count} shares summing to {@code total}, rounded half up. */
    private static int meanShare(long total, int count) {
        return (int) ((2L * total + count) / (2L * count));
    }
}
