package dev.wareworks.core.traffic;

import java.util.Objects;

/**
 * Why a crane does not drive where it wants to right now, and because of whom (issue #14, ADR-075 §5.3, §15). The
 * goggles turn it into a sentence; {@link Reason#name()} is the stable sync name.
 *
 * @param reason     why
 * @param otherCrane the number of the crane the reason names, or 0 when it names none
 * @param cell       the track cell the reason is about (the first one held, for {@link Reason#PATH_HELD}), or
 *                   {@link TrackGraph#NONE}
 */
public record TrafficWait(Reason reason, int otherCrane, int cell) {
    /** Why a request was not granted. */
    public enum Reason {
        /** The rails do not lead there from where the crane stands. */
        NO_PATH,
        /** Another crane holds a rail of the way: "Waiting for crane 1: it holds the rails at A-07". */
        PATH_HELD,
        /** A crane that cannot move (no rotation, overstressed) holds a rail of the way. */
        CRANE_STOPPED,
        /** A crane whose chunk is not loaded holds a rail of the way. */
        CRANE_UNLOADED,
        /** An older request needs these rails, or this stop would put the crane in its way: "Giving way to crane 1". */
        GIVING_WAY,
        /** Standing there would cut another crane off from its dock. */
        WOULD_CUT_OFF,
        /** The warehouse controller is not loaded, so nobody can grant anything (set by the content layer). */
        NO_CONTROLLER
    }

    public TrafficWait {
        Objects.requireNonNull(reason, "reason");
        if (otherCrane < 0)
            throw new IllegalArgumentException("otherCrane must not be negative: " + otherCrane);
        if (cell < TrackGraph.NONE)
            throw new IllegalArgumentException("cell must be a cell or NONE: " + cell);
    }

    public static TrafficWait of(Reason reason) {
        return new TrafficWait(reason, 0, TrackGraph.NONE);
    }

    public static TrafficWait of(Reason reason, int otherCrane) {
        return new TrafficWait(reason, otherCrane, TrackGraph.NONE);
    }
}
