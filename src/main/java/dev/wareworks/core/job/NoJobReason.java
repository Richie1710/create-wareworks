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
    /**
     * An accepting warehouse port was the only target left for an input station's items and it accepts nothing right
     * now (M17, issue #12): the port's buffer is full, so the input backs up exactly as it does when a warehouse is
     * full — nothing is destroyed and nothing is dropped.
     * <p>
     * Reported <b>instead of</b> {@link #AT_MAXIMUM} when both applied, with M15's own argument turned around: a
     * maximum is not a fault, but an overflow that cannot get rid of its items is the thing to go and fix, so it is the
     * more specific and the more actionable answer. It loses to {@link #WAREHOUSE_FULL}, because a storage location
     * that was ranked and gave nothing is a genuinely full warehouse again.
     * <p>
     * Unlike {@link #AT_MAXIMUM} it <b>does</b> arm the dispatcher's {@code fullBackoffTicks} back-off: reaching it
     * costs a full candidate walk, an estimate and a live simulation per candidate, which is exactly the work
     * {@link #WAREHOUSE_FULL} and {@link #NO_MATCHING_FILTER} back off to protect.
     */
    PORT_FULL,
    /**
     * An input station holds items a stock rule will not let the warehouse store any more: its maximum is reached
     * (M15, issue #3). Told apart from {@link #WAREHOUSE_FULL} and {@link #NO_MATCHING_FILTER} because it is not a
     * fault at all — the input backs up <b>on purpose</b>, and neither a bigger warehouse nor another filter changes
     * it; only taking items out, raising the maximum or removing the rule does. Reported instead of a filter mismatch
     * when both applied, because it is the more specific and the more actionable answer.
     * <p>
     * It is also the one skip reason that must <b>not</b> arm the dispatcher's {@code fullBackoffTicks} back-off: a
     * maximum is answered by a single lookup before any candidate, estimate or live call, so there is no expensive
     * scan to protect, and holding storing back for every other input of the aisle because one item is capped would
     * be a real fault caused by a working rule.
     */
    AT_MAXIMUM,
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
    /**
     * A <b>collecting</b> warehouse port was the only arrival with anything at all, and the machine behind it hands out
     * nothing the port may fetch right now (M18, issue #13): its filter names none of what is in there, or the inventory
     * refuses to hand it out through the face the port reads.
     * <p>
     * Declared this late on purpose. It is the least alarming and the least actionable of all the reasons — a machine
     * that has nothing ready is the normal resting state of a production loop — so every honest answer of the same run,
     * from a full warehouse down to a request that waits, must win the one line the goggles show.
     * <p>
     * Like {@link #AT_MAXIMUM} it must <b>not</b> arm the dispatcher's {@code fullBackoffTicks} back-off, and for the
     * same reason: reaching it costs one map lookup per item type plus at most one live extract per type, never the
     * candidate walk with an estimate and a live simulation per candidate that the back-off exists to protect. Backing
     * off here would suspend storing from every input station of the aisle because one machine is empty — which is what
     * a collecting port's machine is most of the time.
     */
    COLLECT_SOURCE_EMPTY,
    /** No open request needs a job and no input station holds items. */
    NO_WORK;

    /**
     * Whether this reason is one a <b>collecting</b> port's own goggles may show as "the warehouse did not take these
     * items" (M18, issue #13): the three answers a collect plan really produces about the warehouse, and none of the ones
     * that are about a request, an output or an accepting port.
     * <p>
     * {@link #COLLECT_SOURCE_EMPTY} is deliberately not one of them: it says the machine has nothing ready, which the
     * port's own "Ready: 0" already says better.
     */
    public boolean refusesCollecting() {
        return this == WAREHOUSE_FULL || this == NO_MATCHING_FILTER || this == AT_MAXIMUM;
    }
}
