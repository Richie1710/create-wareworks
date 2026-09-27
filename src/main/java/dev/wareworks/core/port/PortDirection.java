package dev.wareworks.core.port;

/**
 * What a warehouse port does ({@code docs/warehouse-system.md} §3.2, M17, issue #12; M18, issue #13).
 * <p>
 * Derived from the signed rank of {@link PortSettings} rather than stored on its own: rank 0 is a requesting port,
 * {@link PortSettings#COLLECT_RANK} a collecting one and everything else an accepting one, so "which direction" and "in
 * which order among the others" are one number and can never disagree.
 */
public enum PortDirection {
    /** The crane brings what the port's filter names — the warehouse output as it has always worked. */
    REQUEST,
    /**
     * The crane brings items that arrived at an input and would otherwise be stored. What the player built behind the
     * port decides what happens to them; the warehouse itself never destroys anything.
     */
    ACCEPT,
    /**
     * The crane <b>fetches</b> items out of the inventory the port is attached to and stores them like anything else
     * (M18, issue #13): the direction that closes the production loop, so a machine's result chest needs no belt back to
     * an input.
     * <p>
     * It is only ever a <b>source</b>, never a target: a collected item is never handed back out through a port, which
     * is what makes the collect → store → overflow → collect loop structurally impossible ({@code JobType#COLLECT}).
     */
    COLLECT
}
