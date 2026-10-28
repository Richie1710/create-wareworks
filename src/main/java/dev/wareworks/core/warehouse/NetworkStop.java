package dev.wareworks.core.warehouse;

/**
 * Why the discovery of a warehouse rail network ended where it did ({@code docs/warehouse-system.md} §4, ADR-033).
 * <p>
 * A scan always yields a <b>valid, possibly shorter</b> network — never none — so every reason but {@link #END} names
 * something a player can see and fix, and it is reported rather than silently swallowed. {@link #name()} is the stable
 * sync and log name.
 * <p>
 * <b>Since M22 the rails may split and may close on themselves</b> (issue #2), so the two reasons that only ever said
 * "this version cannot follow that shape" are gone: a T, a cross and a ring are ordinary networks. What is left is a
 * real end, something a player built in the way, or a configured maximum — and every maximum names the key to raise.
 */
public enum NetworkStop {
    /** The rails simply end there: nothing is wrong. */
    END,
    /** The next aisle block lies in a chunk that is not loaded, so the network beyond it is unknown. */
    UNLOADED,
    /** The next rail is closed with a wrench, which is exactly what a closed rail is for. */
    CLOSED,
    /**
     * Another stacker crane dock stands there. It is a wall, so both warehouses keep working: a dock with a controller
     * of its own, a dock that does not face into these rails, or — with {@code aisle.maxCranesPerNetwork = 1} — any
     * other dock at all, exactly as up to 0.17.1.
     */
    SECOND_DOCK,
    /** The network reached the configured maximum number of aisle blocks ({@code aisle.maxNetworkRails}). */
    MAX_RAILS,
    /** The network reached the configured maximum number of aisles ({@code aisle.maxBranches}). */
    MAX_BRANCHES,
    /**
     * The network reached the configured maximum number of junctions ({@code aisle.maxJunctions}) — the aisle blocks
     * two aisles share, which is what a route search is priced in (M22, issue #2, {@link RouteCosts}).
     */
    MAX_JUNCTIONS,
    /** A branch reached the configured maximum aisle length ({@code aisle.maxAisleLength}). */
    MAX_LENGTH,
    /**
     * A dock without a controller of its own faces into the rails, but the warehouse already has as many stacker cranes
     * as the server allows ({@code aisle.maxCranesPerNetwork}, issue #14, ADR-075), so that dock is a wall.
     */
    MAX_CRANES;

    /**
     * Whether the rails really end here: nothing a player wanted in the warehouse was cut off. A rail closed with a
     * wrench counts as a real end, because that is exactly what closing it asked for.
     */
    public boolean isComplete() {
        return !isFault();
    }

    /** Whether the network stops short of something a player would want in it. */
    public boolean isFault() {
        return this != END && this != CLOSED;
    }
}
