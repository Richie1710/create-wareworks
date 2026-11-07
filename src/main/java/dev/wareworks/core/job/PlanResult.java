package dev.wareworks.core.job;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Result of {@link JobPlanner#plan}: the planned job if any, the reasons for skipped work, and the input round-robin
 * cursor for the next run.
 *
 * @param job             the planned job
 * @param reasons         what was skipped, in {@link NoJobReason} priority order (may be non-empty with a job, e.g.
 *                        an earlier request's output was full); unmodifiable
 * @param nextArrivalCursor index into the <b>arrival</b> round robin the next run starts at: the input stations followed
 *                        by the collecting warehouse ports, walked as one virtual list from one cursor (M18, issue #13),
 *                        so within one full walk every input and every gated-open collecting port gets its turn and
 *                        neither starves the other
 * @param relocationsExhausted the relocation needs ({@link PlannerInput.RelocateNeed#id()}) whose search ended with
 *                        nothing found while the live simulation budget still had calls left (issue #46): nothing more
 *                        of that key fits anywhere better right now. A need the budget cut short is never in it; empty
 *                        whenever an earlier stage planned the job, because the relocation stage then never ran
 * @param <K>             item key type
 * @param <L>             location type
 */
public record PlanResult<K, L>(Optional<PlannedJob<K, L>> job, Set<NoJobReason> reasons, int nextArrivalCursor,
        Set<UUID> relocationsExhausted) {
    public PlanResult {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(reasons, "reasons");
        reasons = Collections.unmodifiableSet(reasons.isEmpty() ? EnumSet.noneOf(NoJobReason.class)
                : EnumSet.copyOf(reasons));
        if (nextArrivalCursor < 0)
            throw new IllegalArgumentException("nextArrivalCursor must not be negative: " + nextArrivalCursor);
        relocationsExhausted = Set.copyOf(Objects.requireNonNull(relocationsExhausted, "relocationsExhausted"));
    }

    /** A result of a run in which no relocation search ended empty: every result before issue #46. */
    public PlanResult(Optional<PlannedJob<K, L>> job, Set<NoJobReason> reasons, int nextArrivalCursor) {
        this(job, reasons, nextArrivalCursor, Set.of());
    }

    public boolean hasJob() {
        return job.isPresent();
    }

    /** The most relevant reason, empty if nothing was skipped. */
    public Optional<NoJobReason> primaryReason() {
        return reasons.stream().findFirst();
    }
}
