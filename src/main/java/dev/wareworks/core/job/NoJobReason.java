package dev.wareworks.core.job;

/**
 * Why {@link JobPlanner#plan} created no job (or what it skipped before finding one), for goggles and back-off
 * ({@code docs/warehouse-system.md} §7.1). Declared in priority order: the first reason present is the most relevant
 * one to show. {@link #name()} is a stable name.
 */
public enum NoJobReason {
    /**
     * An input station holds items and the storage locations that may take them have no room (back off
     * {@code fullBackoffTicks}). More space or a retrieval relieves it.
     */
    WAREHOUSE_FULL,
    /**
     * An input station holds items that <b>no</b> storage location's filter accepts (M8, ADR-021). Told apart from
     * {@link #WAREHOUSE_FULL} because the cure is different: a retrieval frees space but never makes a filter match,
     * so the player needs an unfiltered location, not a bigger warehouse.
     */
    NO_MATCHING_FILTER,
    /** An open request's output station accepts nothing. */
    OUTPUT_FULL,
    /**
     * A production order's station accepts nothing, so its ingredients cannot be delivered right now (M11, ADR-024).
     * Told apart from {@link #OUTPUT_FULL} because the cure is different: the player's machine has to take what is
     * already in the station's buffer, rather than a funnel having to drain an output.
     */
    PRODUCTION_FULL,
    /** An open request's item is not in stock (or all of it is reserved, or the live inventories disagree). */
    NOT_IN_STOCK,
    /** An open request's output station is unavailable (not loaded or gone). */
    LOCATION_UNAVAILABLE,
    /** The planner ran out of live simulations for this run; the next run continues. */
    BUDGET_EXHAUSTED,
    /** No open request needs a job and no input station holds items. */
    NO_WORK
}
