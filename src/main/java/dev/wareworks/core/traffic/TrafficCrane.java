package dev.wareworks.core.traffic;

import java.util.Objects;
import java.util.Optional;

/**
 * What the traffic ledger reads of one crane at the start of a pass (issue #14, ADR-075 §4.4): where it stands, what
 * it wants and whether it can move. Records compare by value, which is what lets a pass see that nothing changed.
 *
 * @param number       the crane number: 1 for the crane at the warehouse's own dock, then 2, 3, ... in discovery order
 * @param branch       the branch its pose is named on
 * @param x            its position on that branch, fractional while it drives between two blocks
 * @param armRetracted whether it may leave its block this tick without interrupting anything: its arm is in and it is
 *                     not in the middle of a transfer. Only such a crane is ordered to move aside
 * @param health       whether it can move at all
 * @param want         the stop it asks for, or empty
 * @param dockCell     the track cell of its own dock, or {@link TrackGraph#NONE} if the network has none for it
 * @param homeCell     the track cell of the home point assigned to it, or {@link TrackGraph#NONE}: where it parks first
 *                     when it has to move aside
 */
public record TrafficCrane(int number, int branch, double x, boolean armRetracted, TrafficHealth health,
                           Optional<TrafficWant> want, int dockCell, int homeCell) {
    public TrafficCrane {
        if (number < 1)
            throw new IllegalArgumentException("crane numbers start at 1: " + number);
        if (!Double.isFinite(x))
            throw new IllegalArgumentException("x must be finite: " + x);
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(want, "want");
        if (dockCell < TrackGraph.NONE || homeCell < TrackGraph.NONE)
            throw new IllegalArgumentException("cells must be cells or NONE: " + dockCell + ", " + homeCell);
    }

    /** The same crane standing at another pose. */
    public TrafficCrane at(int newBranch, double newX) {
        return new TrafficCrane(number, newBranch, newX, armRetracted, health, want, dockCell, homeCell);
    }

    /** The same crane with another want. */
    public TrafficCrane wanting(Optional<TrafficWant> newWant) {
        return new TrafficCrane(number, branch, x, armRetracted, health, newWant, dockCell, homeCell);
    }

    /** The same crane in another state of health. */
    public TrafficCrane withHealth(TrafficHealth newHealth) {
        return new TrafficCrane(number, branch, x, armRetracted, newHealth, want, dockCell, homeCell);
    }

    /** The same crane with its arm in or out. */
    public TrafficCrane withArmRetracted(boolean retracted) {
        return new TrafficCrane(number, branch, x, retracted, health, want, dockCell, homeCell);
    }
}
