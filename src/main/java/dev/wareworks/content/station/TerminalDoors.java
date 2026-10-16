package dev.wareworks.content.station;

import java.util.List;
import java.util.Objects;

/**
 * The package doors a terminal's screen may deliver to, in rack order, and how many more the list leaves out (M27,
 * issue #18, ADR-065 D9). Compared by value between pushes, so a warehouse whose doors did not change costs no packet.
 *
 * @param rows      at most {@value #MAX_ROWS} doors
 * @param notListed doors found beyond those rows, which the screen names as "+N more"
 * @param unchecked whether the server stopped looking before the last output of the warehouse
 *                  ({@code DoorDirectory#MAX_DOOR_CANDIDATES}), which the screen says, because doors behind that cut
 *                  are neither listed nor counted
 */
public record TerminalDoors(List<TerminalDoorRow> rows, int notListed, boolean unchecked) {
    /** Rows one push carries at most. */
    public static final int MAX_ROWS = 16;
    /** No door at all: a warehouse without one, or a terminal outside every warehouse. */
    public static final TerminalDoors NONE = new TerminalDoors(List.of(), 0, false);

    public TerminalDoors {
        Objects.requireNonNull(rows, "rows");
        int over = Math.max(0, rows.size() - MAX_ROWS);
        rows = List.copyOf(rows.subList(0, rows.size() - over));
        notListed = Math.max(0, notListed) + over;
    }

    /** Doors from a build that checked every output. */
    public TerminalDoors(List<TerminalDoorRow> rows, int notListed) {
        this(rows, notListed, false);
    }

    /** Whether there is no door to choose from, in which case the screen shows no choice at all. */
    public boolean isEmpty() {
        return rows.isEmpty() && notListed == 0;
    }
}
