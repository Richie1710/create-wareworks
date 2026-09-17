package dev.wareworks.core.crane;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.job.CraneSpeeds;
import dev.wareworks.core.job.TransportJob;
import dev.wareworks.core.warehouse.LocationKind;

/**
 * Inputs of {@link CraneStateMachine#apply}: time, job assignment, results of world operations the block entity
 * performed for an effect, and observations about the world. Events that do not apply to the current phase change
 * nothing ({@link CraneStateMachine.Transition#changed()} is false).
 *
 * @param <K> item key type
 * @param <L> location type
 */
public sealed interface CraneEvent<K, L> {
    /** One server (or client) tick with the current axis speeds; all-zero speeds pause like {@link Paused}. */
    record Tick<K, L>(CraneSpeeds speeds) implements CraneEvent<K, L> {
        public Tick {
            Objects.requireNonNull(speeds, "speeds");
        }
    }

    /** A new job for an idle crane (not picked yet). */
    record JobAssigned<K, L>(TransportJob<K, L> job) implements CraneEvent<K, L> {
        public JobAssigned {
            Objects.requireNonNull(job, "job");
        }
    }

    /** Real extraction result for {@link CraneEffect.PerformPick}: {@code 0 … planned} items are now in the head. */
    record PickResult<K, L>(int picked) implements CraneEvent<K, L> {
        public PickResult {
            if (picked < 0)
                throw new IllegalArgumentException("picked must not be negative: " + picked);
        }
    }

    /** Real insertion result for {@link CraneEffect.PerformDrop}: delivered + leftover equals the held amount. */
    record DropResult<K, L>(int delivered, int leftover) implements CraneEvent<K, L> {
        public DropResult {
            if (delivered < 0 || leftover < 0)
                throw new IllegalArgumentException("amounts must not be negative: " + delivered + ", " + leftover);
        }
    }

    /** Answer to {@link CraneEffect.RequestReroute}: a new target, or none (hold the items). */
    record RerouteResult<K, L>(Optional<L> target, Optional<LocationKind> kind) implements CraneEvent<K, L> {
        public RerouteResult {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(kind, "kind");
            if (target.isPresent() != kind.isPresent())
                throw new IllegalArgumentException("target and kind must both be present or both be empty");
        }
    }

    /** The output station the crane is serving accepts nothing right now. */
    record OutputFull<K, L>() implements CraneEvent<K, L> {
    }

    /** The job's target location or its inventory is gone. */
    record TargetMissing<K, L>() implements CraneEvent<K, L> {
    }

    /** The job's source location or its inventory is gone (relevant only before the pick). */
    record SourceMissing<K, L>() implements CraneEvent<K, L> {
    }

    /** The controller cancelled the job (request or output gone): abort before the pick, reroute after it. */
    record JobCancelled<K, L>() implements CraneEvent<K, L> {
    }

    /** Motion and timers stop (no rotation, overstressed, a needed chunk not loaded). */
    record Paused<K, L>() implements CraneEvent<K, L> {
    }

    /** Motion and timers continue. */
    record Resumed<K, L>() implements CraneEvent<K, L> {
    }

    static <K, L> CraneEvent<K, L> tick(CraneSpeeds speeds) {
        return new Tick<>(speeds);
    }

    static <K, L> CraneEvent<K, L> jobAssigned(TransportJob<K, L> job) {
        return new JobAssigned<>(job);
    }

    static <K, L> CraneEvent<K, L> pickResult(int picked) {
        return new PickResult<>(picked);
    }

    static <K, L> CraneEvent<K, L> dropResult(int delivered, int leftover) {
        return new DropResult<>(delivered, leftover);
    }

    static <K, L> CraneEvent<K, L> rerouteTo(L target, LocationKind kind) {
        return new RerouteResult<>(Optional.of(target), Optional.of(kind));
    }

    static <K, L> CraneEvent<K, L> noReroute() {
        return new RerouteResult<>(Optional.empty(), Optional.empty());
    }

    static <K, L> CraneEvent<K, L> outputFull() {
        return new OutputFull<>();
    }

    static <K, L> CraneEvent<K, L> targetMissing() {
        return new TargetMissing<>();
    }

    static <K, L> CraneEvent<K, L> sourceMissing() {
        return new SourceMissing<>();
    }

    static <K, L> CraneEvent<K, L> jobCancelled() {
        return new JobCancelled<>();
    }

    static <K, L> CraneEvent<K, L> paused() {
        return new Paused<>();
    }

    static <K, L> CraneEvent<K, L> resumed() {
        return new Resumed<>();
    }
}
