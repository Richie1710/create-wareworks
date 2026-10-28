package dev.wareworks.core.traffic;

/**
 * Whether a crane takes part in traffic as a machine that can move, or only as an obstacle (issue #14, ADR-075 §12).
 * <p>
 * Only a {@link #HEALTHY} crane is promised progress. Every other one keeps its claim, so nothing ever drives through
 * it, but the cranes that would have to pass it wait until a player fixes it — and say why. {@link #name()} is the
 * stable save and sync name.
 */
public enum TrafficHealth {
    /** Linked, loaded, turning and able to reach its own dock: requests are served and its escape is guaranteed. */
    HEALTHY,
    /** No rotation, overstressed or a speed of zero: it cannot move, so its claim stays and it asks for nothing. */
    STOPPED,
    /** Its block entity is not loaded: its last known claim stays, a superset of where it can be. */
    UNLOADED,
    /**
     * Its own dock cannot be reached from where its machine stands — a rail was closed between them. It may still
     * finish the job it carries inside the warehouse, but no escape is required for it and it gets no new jobs.
     */
    STRANDED;

    /** Whether a crane in this state may be granted a route at all. */
    public boolean canMove() {
        return this == HEALTHY || this == STRANDED;
    }
}
