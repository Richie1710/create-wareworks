package dev.wareworks.core.crane;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.job.TransportJob;

/**
 * Outputs of {@link CraneStateMachine#apply}: world operations the block entity performs, reports for the controller
 * ({@code docs/warehouse-system.md} §7.3) and sync hints. The machine never touches the world itself.
 * <p>
 * <b>Perform effects</b> must be answered in the same tick, before anything else happens: {@link PerformPick} with
 * {@link CraneEvent.PickResult}, {@link PerformDrop} with {@link CraneEvent.DropResult} (or
 * {@link CraneEvent.OutputFull} / {@link CraneEvent.TargetMissing} without touching the inventory, or
 * {@link CraneEvent.Exchanged} when the stop was a container exchange),
 * {@link RequestReroute} with {@link CraneEvent.RerouteResult}. If the block entity cannot perform one now (chunk not
 * loaded), it sends nothing: the machine repeats a perform effect every unpaused tick, and treats an unanswered reroute
 * as "none" on the next tick.
 *
 * @param <K> item key type
 * @param <L> location type
 */
public sealed interface CraneEffect<K, L> {
    /** Extract up to {@code amount} items of the job's key from {@code source} into the handling head (real call). */
    record PerformPick<K, L>(TransportJob<K, L> job, L source, int amount) implements CraneEffect<K, L> {
        public PerformPick {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(source, "source");
        }
    }

    /** Insert the {@code amount} held items into {@code target} (real call); leftovers stay in the head. */
    record PerformDrop<K, L>(TransportJob<K, L> job, L target, int amount) implements CraneEffect<K, L> {
        public PerformDrop {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Find a new target for {@code amount} held items. {@code failedTarget} is the job's current target when it just
     * failed (a drop left items, the target is missing, the job was cancelled), so the planner skips it; it is empty
     * for a {@code HOLDING} retry, which may choose the former target again once it accepts items.
     */
    record RequestReroute<K, L>(TransportJob<K, L> job, Optional<L> failedTarget, int amount)
            implements CraneEffect<K, L> {
        public RequestReroute {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(failedTarget, "failedTarget");
        }
    }

    /** The pick happened ({@code onPicked}): {@code picked} items are in the head; the job carries the new state. */
    record ReportPicked<K, L>(TransportJob<K, L> job, int picked) implements CraneEffect<K, L> {
        public ReportPicked {
            Objects.requireNonNull(job, "job");
        }
    }

    /** {@code delivered} (at least 1) items reached {@code target} ({@code onDelivered}). */
    record ReportDelivered<K, L>(TransportJob<K, L> job, L target, int delivered) implements CraneEffect<K, L> {
        public ReportDelivered {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * A <b>container exchange</b> happened at {@code target}: {@code amount} items of {@code formerKey} were given up
     * there and {@code amount} of {@code job.key()} came back ({@code onCraneExchanged}, M30, issue #21, D14).
     * <p>
     * <b>Why this is never a {@link ReportDelivered}</b>, which is the one decision of this record: a controller credits
     * a delivery into storage to whatever ordered the items — {@code onCraneDelivered} runs {@code onResultStored} for
     * every job that brings items in and delivered more than nothing into a {@code STORAGE} location. An exchange
     * reported that way would credit a restock order with a filled container that was never stored, the stock index
     * would show none of it, and ADR-027's safety stop would fire for no reason. Nothing was delivered here: the fluid
     * went into a tank and the containers it came out of are still in the head.
     *
     * @param job       the job after the exchange: same id, source, target and type, with {@code key} the item the head
     *                  now holds and nothing delivered yet
     * @param formerKey the item that was given up — the one piece of what happened that {@code job} no longer carries
     * @param target    where it happened (the job's target as well, until the reroute moves it)
     * @param amount    how many containers were exchanged, at least 1
     */
    record ReportExchanged<K, L>(TransportJob<K, L> job, K formerKey, L target, int amount)
            implements CraneEffect<K, L> {
        public ReportExchanged {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(formerKey, "formerKey");
            Objects.requireNonNull(target, "target");
            if (amount < 1)
                throw new IllegalArgumentException("an exchange moves at least one container: " + amount);
        }
    }

    /** The job's leftovers have a new target (the job carries it). */
    record ReportRerouted<K, L>(TransportJob<K, L> job) implements CraneEffect<K, L> {
        public ReportRerouted {
            Objects.requireNonNull(job, "job");
        }
    }

    /** Everything picked was delivered; release the job's reservations. */
    record ReportComplete<K, L>(TransportJob<K, L> job) implements CraneEffect<K, L> {
        public ReportComplete {
            Objects.requireNonNull(job, "job");
        }
    }

    /** The job ended with nothing held ({@code onJobAborted}); release its reservations, requests keep their amount. */
    record ReportAbort<K, L>(TransportJob<K, L> job, AbortReason reason) implements CraneEffect<K, L> {
        public ReportAbort {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The phase changed (sync hint for the block entity). */
    record PhaseChanged<K, L>(CranePhase from, CranePhase to) implements CraneEffect<K, L> {
        public PhaseChanged {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }
}
