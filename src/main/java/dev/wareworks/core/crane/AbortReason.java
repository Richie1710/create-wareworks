package dev.wareworks.core.crane;

/**
 * Why a crane gave up a job before picking anything ({@link CraneEffect.ReportAbort}). A job with items in the handling
 * head is never aborted; its items are rerouted or held instead. {@link #name()} is a stable name.
 */
public enum AbortReason {
    /** The real extraction returned nothing ({@code docs/warehouse-system.md} §8). */
    ZERO_PICK,
    /** The source location or its inventory disappeared before the pick. */
    SOURCE_MISSING,
    /** The target disappeared before the pick; the job should be planned again. */
    TARGET_MISSING,
    /** The controller cancelled the job (e.g. its request or output station is gone). */
    CANCELLED
}
