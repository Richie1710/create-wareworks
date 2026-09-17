package dev.wareworks.core.crane;

/**
 * An event that interrupted a stop while the arm was extended; the crane retracts first and then acts on it
 * ({@link CraneState#interruption()}). {@link #name()} is the stable save name.
 */
public enum CraneInterruption {
    /** Source gone before the pick: abort after retracting. */
    SOURCE_MISSING(AbortReason.SOURCE_MISSING, false),
    /** Target gone: abort after retracting if nothing is held, otherwise reroute. */
    TARGET_MISSING(AbortReason.TARGET_MISSING, true),
    /** Job cancelled: abort after retracting if nothing is held, otherwise reroute. */
    CANCELLED(AbortReason.CANCELLED, true),
    /** Output station full: wait after retracting (only with held items, so it never aborts). */
    OUTPUT_FULL(AbortReason.CANCELLED, false);

    private final AbortReason abortReason;
    private final boolean reroutesHeldItems;

    CraneInterruption(AbortReason abortReason, boolean reroutesHeldItems) {
        this.abortReason = abortReason;
        this.reroutesHeldItems = reroutesHeldItems;
    }

    /** The reason reported when the interruption ends a job with nothing held. */
    public AbortReason abortReason() {
        return abortReason;
    }

    /** Whether held items go to {@link CranePhase#REROUTE} instead of continuing to the target. */
    public boolean reroutesHeldItems() {
        return reroutesHeldItems;
    }

    /** The interruption to keep when {@code incoming} arrives while {@code this} is pending. */
    CraneInterruption merge(CraneInterruption incoming) {
        return this == OUTPUT_FULL ? incoming : this;
    }
}
