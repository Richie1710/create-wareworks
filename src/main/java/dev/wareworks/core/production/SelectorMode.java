package dev.wareworks.core.production;

/**
 * How a production station chooses the recipe of its machine (ADR-085, {@code docs/warehouse-system.md} §3.5.9).
 * <p>
 * In both selector modes the station's line holds the ingredients of <b>one result at a time</b>, and the station only
 * switches to another result once the line is drained ({@link SelectorGate}).
 */
public enum SelectorMode {
    /** No Recipe Selector: the station's orders are dispatched as they always were. */
    NONE,
    /**
     * A Recipe Selector next to the station's own machine sets that machine's recipe filter (the local tier).
     */
    LOCAL,
    /**
     * An Ender Recipe Selector at a far factory reads the recipe slip the station writes into every box its Packager
     * sends (the far tier).
     */
    SLIPS;

    /** Whether the station's orders go through {@link SelectorGate} at all. */
    public boolean selects() {
        return this != NONE;
    }
}
