package dev.wareworks.core.warehouse;

/**
 * Why the discovery of a warehouse rail network ended where it did ({@code docs/warehouse-system.md} §4, ADR-033).
 * <p>
 * A scan always yields a <b>valid, possibly shorter</b> network — never none — so every reason but {@link #END} names
 * something a player can see and fix, and it is reported rather than silently swallowed. {@link #name()} is the stable
 * sync and log name.
 */
public enum NetworkStop {
    /** The rails simply end there: nothing is wrong. */
    END,
    /** The next aisle block lies in a chunk that is not loaded, so the network beyond it is unknown. */
    UNLOADED,
    /** The next rail is closed with a wrench, which is exactly what a closed rail is for. */
    CLOSED,
    /** Another stacker crane dock stands there. It is a wall, so both warehouses keep working. */
    SECOND_DOCK,
    /** The rails split there. Step one of M21 follows a chain only, so the rails beyond are not part of this network. */
    BRANCHED,
    /**
     * The rails lead back onto a block the scan already took. Cannot normally happen — the first block of a ring has
     * three connections and is reported as {@link #BRANCHED} — and exists so that a walk can never run for ever.
     */
    LOOPED,
    /** The network reached the configured maximum number of aisle blocks ({@code aisle.maxNetworkRails}). */
    MAX_RAILS,
    /** The network reached the configured maximum number of branches ({@code aisle.maxBranches}). */
    MAX_BRANCHES,
    /** A branch reached the configured maximum aisle length ({@code aisle.maxAisleLength}). */
    MAX_LENGTH;

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
