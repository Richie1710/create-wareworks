package dev.wareworks.core.traffic;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * The escape invariant of several cranes on one warehouse (issue #14, ADR-075 §5.1):
 * <blockquote><b>I3.</b> For every healthy crane {@code k} there is a path in the track graph from where it will stand
 * to its own dock that avoids where every other crane will stand.</blockquote>
 * Informally: <b>no crane may ever cut another off from its dock</b>. On a line with a dock at each end that is "no
 * passing" — the cranes keep their order. On a comb the teeth are sidings, so two cranes do get past each other, one
 * waiting in a tooth, without ever sharing a rail. On a ring they go round.
 * <p>
 * "Where a crane will stand" is its <b>settled</b> cells: the stop of the route it is committed to, or the blocks it
 * stands on without one. The traffic ledger grants a route only if no crane loses its escape by it, and the whole
 * deadlock argument of ADR-075 rests on this check together with every dock being a dead end of the rails.
 * <p>
 * Pure: one breadth-first search per crane over at most a thousand cells.
 */
public final class EscapeCheck {
    private EscapeCheck() {
    }

    /**
     * Whether a crane settled on {@code from} can reach {@code dock} without entering a {@code blocked} cell.
     *
     * @param dock its dock cell; {@link TrackGraph#NONE} never has an escape
     */
    public static boolean hasEscape(TrackGraph graph, int[] from, int dock, BitSet blocked) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(from, "from");
        if (dock < 0 || from.length == 0)
            return false;
        for (int cell : from) {
            if (cell == dock)
                return true;
        }
        if (blocked.get(dock))
            return false;
        return graph.canReach(from, dock, blocked);
    }

    /**
     * The cranes, by their index in the lists, that are {@code required} to have an escape and have none: the
     * {@code k} for which every path from {@code settled.get(k)} to {@code docks[k]} enters a cell another crane is
     * settled on. Empty is I3.
     *
     * @param settled  per crane the cells it is settled on
     * @param docks    per crane its dock cell
     * @param required per crane whether I3 is required for it (a healthy crane); the others are obstacles only
     */
    public static BitSet cutOff(TrackGraph graph, List<int[]> settled, int[] docks, boolean[] required) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(settled, "settled");
        Objects.requireNonNull(docks, "docks");
        Objects.requireNonNull(required, "required");
        int cranes = settled.size();
        if (docks.length != cranes || required.length != cranes)
            throw new IllegalArgumentException("one dock and one flag per crane");
        int[] holders = new int[graph.cellCount()];
        for (int[] cells : settled) {
            for (int cell : cells) {
                if (cell >= 0)
                    holders[cell]++;
            }
        }
        BitSet occupied = new BitSet(graph.cellCount());
        for (int cell = 0; cell < holders.length; cell++) {
            if (holders[cell] > 0)
                occupied.set(cell);
        }
        BitSet cut = new BitSet(cranes);
        for (int k = 0; k < cranes; k++) {
            if (!required[k])
                continue;
            int[] own = settled.get(k);
            BitSet blocked = (BitSet) occupied.clone();
            for (int cell : own) {
                if (cell >= 0 && holders[cell] == 1)
                    blocked.clear(cell);
            }
            if (!hasEscape(graph, own, docks[k], blocked))
                cut.set(k);
        }
        return cut;
    }
}
