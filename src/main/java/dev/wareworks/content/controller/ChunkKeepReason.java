package dev.wareworks.content.controller;

import java.util.Locale;

/**
 * Why an aisle is holding its chunks loaded right now, or why it is not (M19, issue #10, ADR-031). Modelled on
 * {@code CranePauseReason}: {@link #name()} is the stable sync name and {@link #langKey()} the goggle text.
 * <p>
 * The reasons that <b>hold</b> ({@link #CRANE_JOB} .. {@link #RELEASING}) and the reasons that <b>refuse</b>
 * ({@link #AT_LEVEL_LIMIT} .. {@link #GAVE_UP}) are told apart by {@link #isHolding()}, so one enum carries the whole
 * goggle line without a second flag in the synced summary. <b>Every refusal has a constant of its own</b>: a cap that
 * changes what an aisle does and reports nothing is invisible to the operator who set it (M19 review).
 */
public enum ChunkKeepReason {
    /** Nothing to report: the feature is off, or the aisle is idle and holds nothing. */
    NONE,
    /** The linked crane has a job. */
    CRANE_JOB,
    /** The aisle has open retrieval requests. */
    OPEN_REQUESTS,
    /** The aisle has open production orders (an automatic restock order is one of them). */
    PRODUCTION_ORDERS,
    /** A gated-open collecting port has something pending; only with the separate opt-in of {@code chunkLoading}. */
    COLLECTING,
    /** Idle, still holding: the linger before the release ({@code chunkLoading.releaseDelayTicks}). */
    RELEASING,
    /** Nothing held: as many aisles of this dimension hold chunks as {@code maxTicketedAislesPerLevel} allows. */
    AT_LEVEL_LIMIT,
    /**
     * Nothing held: as many aisles of this dimension hold chunks <b>for collecting alone</b> as
     * {@code maxCollectHoldAislesPerLevel} allows. Told apart from {@link #AT_LEVEL_LIMIT} on purpose: the two caps are
     * two settings, and an operator who raised the collect opt-in to 2 has to be able to see which aisles are queued
     * behind it (M19 review).
     */
    AT_COLLECT_LIMIT,
    /** Nothing held: this aisle's footprint needs more chunks than {@code maxChunksPerAisle} allows. */
    TOO_MANY_CHUNKS,
    /**
     * Nothing held: the aisle let go although it still has work, and may only hold again once that work really changes
     * (another job, another set of requests or orders) or the work is gone altogether.
     * <p>
     * Two things reach this state: the aisle held its chunks for {@code maxHoldTicks} without finishing, and an
     * operator's {@code /wareworks chunks release}. The goggle line is therefore about what the aisle is doing, not
     * about which of the two it was — the cause is in the server log (a WARN for the first) and in the command's own
     * answer (for the second).
     * <p>
     * The flag behind it is <b>saved with the controller</b>, so the bound holds across a reload and a restart and not
     * only for as long as one block entity instance lives (M19 review). An aisle that becomes idle drops it again,
     * which is also the escape hatch: work that finished re-arms the aisle whatever its fingerprint was.
     */
    GAVE_UP;

    private static final String LANG_PREFIX = "gui.goggles.chunk_keep_reason.";

    /** Whether this reason describes an aisle that <b>is</b> holding chunks. */
    public boolean isHolding() {
        return this == CRANE_JOB || this == OPEN_REQUESTS || this == PRODUCTION_ORDERS || this == COLLECTING
                || this == RELEASING;
    }

    /** Relative lang key of the reason text, e.g. {@code gui.goggles.chunk_keep_reason.crane_job}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /** The reason with the given sync name, or {@link #NONE} for {@code null} or unknown names. */
    public static ChunkKeepReason byName(String name) {
        for (ChunkKeepReason reason : values()) {
            if (reason.name().equals(name))
                return reason;
        }
        return NONE;
    }
}
