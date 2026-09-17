package dev.wareworks.core.job;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Result of {@link JobPlanner#plan}: the planned job if any, the reasons for skipped work, and the input round-robin
 * cursor for the next run.
 *
 * @param job             the planned job
 * @param reasons         what was skipped, in {@link NoJobReason} priority order (may be non-empty with a job, e.g.
 *                        an earlier request's output was full); unmodifiable
 * @param nextInputCursor index of the input station the next run starts at
 * @param <K>             item key type
 * @param <L>             location type
 */
public record PlanResult<K, L>(Optional<PlannedJob<K, L>> job, Set<NoJobReason> reasons, int nextInputCursor) {
    public PlanResult {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(reasons, "reasons");
        reasons = Collections.unmodifiableSet(reasons.isEmpty() ? EnumSet.noneOf(NoJobReason.class)
                : EnumSet.copyOf(reasons));
        if (nextInputCursor < 0)
            throw new IllegalArgumentException("nextInputCursor must not be negative: " + nextInputCursor);
    }

    public boolean hasJob() {
        return job.isPresent();
    }

    /** The most relevant reason, empty if nothing was skipped. */
    public Optional<NoJobReason> primaryReason() {
        return reasons.stream().findFirst();
    }
}
