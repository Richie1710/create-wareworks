package dev.wareworks.content.crane;

import java.util.Locale;
import java.util.Optional;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;

/**
 * What the goggles of one crane of a warehouse with <b>several</b> cranes say about the others (issue #14, ADR-075
 * §15.1), as synced inside {@link CraneGoggleInfo}: which crane it is, why it waits and for whom, which rails it has
 * reserved, and how much of the warehouse's height its mast serves.
 * <p>
 * <b>Absent for a warehouse of one crane</b>, so such a dock's goggle tag is byte for byte what it was up to 0.17.1:
 * {@link CraneGoggleInfo} never carries one, and nothing here is ever built for it.
 * <p>
 * Pure Java and bounded: a dozen small numbers ({@link #pack()}), whatever the warehouse looks like. The remaining
 * trip is deliberately <b>not</b> in here — the client computes it from the pose and the job it already has
 * ({@code core.crane.RemainingTrip}), so it never costs a packet. Reading never throws.
 *
 * @param number        this crane's number, from 1
 * @param craneCount    how many cranes the warehouse has, at least 2
 * @param line          why the crane does not drive where it wants to, or {@link Line#NONE}
 * @param otherCrane    the crane the line names, or 0
 * @param heldBranch    the branch of the rail the other crane holds, or {@value #NO_POSITION}
 * @param heldX         that rail's position on its branch, or {@value #NO_POSITION}
 * @param fromBranch    where the stretch of rails this crane has reserved starts: its branch, or
 *                      {@value #NO_POSITION} without a reservation
 * @param fromX         and the position on it
 * @param toBranch      where that stretch ends — the stop it drives to — and its branch
 * @param toX           and the position on it
 * @param servedLevels  how many levels this crane's mast reaches when that is fewer than the warehouse has; 0 when it
 *                      reaches all of them
 */
public record CraneTrafficInfo(int number, int craneCount, Line line, int otherCrane, int heldBranch, int heldX,
                               int fromBranch, int fromX, int toBranch, int toX, int servedLevels) {
    /** Stands for "no such rail" in every position of this record. */
    public static final int NO_POSITION = -1;
    /** Number of ints {@link #pack()} writes. */
    public static final int PACKED_LENGTH = 11;

    /**
     * What a crane's traffic line says. {@link #name()} is only used inside client packets, where it travels by
     * ordinal, so a new constant goes at the end.
     */
    public enum Line {
        /** Nothing to say: it drives, works or rests. */
        NONE,
        /** "Waiting for crane 1: it holds the rails at A-07". */
        PATH_HELD,
        /** "Waiting for crane 2: it is stopped" — no rotation, overstressed or too slow. */
        CRANE_STOPPED,
        /** "Waiting for crane 2: its chunk is not loaded". */
        CRANE_UNLOADED,
        /** "Giving way to crane 1": an older request needs these rails. */
        GIVING_WAY,
        /** "Waiting: this stop would cut crane 1 off from its dock". */
        WOULD_CUT_OFF,
        /** "Waiting: no rails lead to its next stop". */
        NO_PATH,
        /** "Waiting: the warehouse controller is not loaded". */
        NO_CONTROLLER,
        /** "Moving aside for crane 3". */
        MOVING_ASIDE,
        /** "Way to its parking spot blocked by crane 1": an idle crane that cannot get home. */
        PARK_BLOCKED,
        /** "Cut off from its dock": a rail between the machine and its dock was closed or taken away. */
        STRANDED;

        /** Relative lang key of the goggle sentence, e.g. {@code gui.goggles.crane_traffic.path_held}. */
        public String langKey() {
            return "gui.goggles.crane_traffic." + name().toLowerCase(Locale.ROOT);
        }

        /** Whether the sentence names another crane ({@code %1$s}). */
        public boolean namesCrane() {
            return switch (this) {
                case PATH_HELD, CRANE_STOPPED, CRANE_UNLOADED, GIVING_WAY, WOULD_CUT_OFF, MOVING_ASIDE,
                        PARK_BLOCKED -> true;
                case NONE, NO_PATH, NO_CONTROLLER, STRANDED -> false;
            };
        }

        /** Whether the crane stands still because of this line, as opposed to driving or being cut off. */
        public boolean isWaiting() {
            return switch (this) {
                case PATH_HELD, CRANE_STOPPED, CRANE_UNLOADED, GIVING_WAY, WOULD_CUT_OFF, NO_PATH, NO_CONTROLLER,
                        PARK_BLOCKED -> true;
                case NONE, MOVING_ASIDE, STRANDED -> false;
            };
        }
    }

    public CraneTrafficInfo {
        number = Math.max(1, number);
        craneCount = Math.max(Math.max(2, number), craneCount);
        if (line == null)
            line = Line.NONE;
        otherCrane = line.namesCrane() ? Math.max(0, otherCrane) : 0;
        if (!isRail(heldBranch, heldX)) {
            heldBranch = NO_POSITION;
            heldX = NO_POSITION;
        }
        if (!isRail(fromBranch, fromX) || !isRail(toBranch, toX)) {
            fromBranch = NO_POSITION;
            fromX = NO_POSITION;
            toBranch = NO_POSITION;
            toX = NO_POSITION;
        }
        servedLevels = Math.clamp(servedLevels, 0, AisleGeometry.MAX_HEIGHT);
    }

    /** Crane {@code number} of {@code craneCount}, with nothing to report. */
    public static CraneTrafficInfo quiet(int number, int craneCount, int servedLevels) {
        return new CraneTrafficInfo(number, craneCount, Line.NONE, 0, NO_POSITION, NO_POSITION, NO_POSITION,
                NO_POSITION, NO_POSITION, NO_POSITION, servedLevels);
    }

    private static boolean isRail(int branch, int x) {
        return branch >= RackPosition.FIRST_BRANCH && branch <= RackPosition.MAX_BRANCH && x >= 0
                && x <= AisleGeometry.MAX_LENGTH;
    }

    /**
     * Whether this line says the crane waits for another crane, and names it: "waiting for crane 2". A crane held for
     * no crane at all (no rails lead there, no controller) waits for nobody a player could look for.
     */
    public boolean waitsForACrane() {
        return line.isWaiting() && line.namesCrane() && otherCrane > 0;
    }

    /** Whether the line names the rail the other crane holds. */
    public boolean hasHeldRail() {
        return heldBranch != NO_POSITION;
    }

    /** Whether this crane has reserved a stretch of rails beyond the blocks it stands on. */
    public boolean hasReservation() {
        return fromBranch != NO_POSITION;
    }

    /** This record with another line, other crane and held rail; everything else stays. */
    public CraneTrafficInfo withLine(Line newLine, int newOther, int newHeldBranch, int newHeldX) {
        return new CraneTrafficInfo(number, craneCount, newLine, newOther, newHeldBranch, newHeldX, fromBranch, fromX,
                toBranch, toX, servedLevels);
    }

    /** This record with another reserved stretch, or none with {@value #NO_POSITION}. */
    public CraneTrafficInfo withReservation(int newFromBranch, int newFromX, int newToBranch, int newToX) {
        return new CraneTrafficInfo(number, craneCount, line, otherCrane, heldBranch, heldX, newFromBranch, newFromX,
                newToBranch, newToX, servedLevels);
    }

    /** The numbers in a fixed order, the line by ordinal. */
    public int[] pack() {
        return new int[] {number, craneCount, line.ordinal(), otherCrane, heldBranch, heldX, fromBranch, fromX,
                toBranch, toX, servedLevels};
    }

    /**
     * Reads what {@link #pack()} wrote. Untrusted packet data: anything of the wrong length, or naming a crane count
     * below two, is no traffic info at all, and an unknown line reads as {@link Line#NONE}. Never throws.
     */
    public static Optional<CraneTrafficInfo> unpack(int[] packed) {
        if (packed == null || packed.length != PACKED_LENGTH || packed[1] < 2 || packed[0] < 1)
            return Optional.empty();
        Line[] lines = Line.values();
        Line line = packed[2] >= 0 && packed[2] < lines.length ? lines[packed[2]] : Line.NONE;
        return Optional.of(new CraneTrafficInfo(packed[0], packed[1], line, packed[3], packed[4], packed[5], packed[6],
                packed[7], packed[8], packed[9], packed[10]));
    }
}
