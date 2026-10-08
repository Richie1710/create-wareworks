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

    /**
     * Real <b>container exchange</b> result for {@link CraneEffect.PerformDrop}, the fourth legal answer to it (M30,
     * issue #21, D1): the {@code amount} items the head carried are gone into the target and {@code amount} items of
     * {@code newKey} are in the head instead — a filled container became fluid in a fluid bay's tank and an empty
     * container in the head.
     * <p>
     * It is a separate event from {@link DropResult} and not a special case of it, because a drop result is defined as
     * {@code delivered + leftover == heldAmount} of the job's <b>own</b> key ({@link CraneStateMachine}'s item
     * conservation rules): an exchange delivers nothing of that key and leaves nothing of it either. The job that comes
     * out of it carries {@code newKey} and the same count, so the head and the job stay in step and the existing
     * reroute ladder puts the empty containers away like any other carry nobody asked for.
     *
     * @param newKey the key the head now holds, never the one it held before
     * @param amount how many of them, which must be exactly what the job held
     */
    record Exchanged<K, L>(K newKey, int amount) implements CraneEvent<K, L> {
        public Exchanged {
            Objects.requireNonNull(newKey, "newKey");
            if (amount < 1)
                throw new IllegalArgumentException("an exchange moves at least one container: " + amount);
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

    static <K, L> CraneEvent<K, L> exchanged(K newKey, int amount) {
        return new Exchanged<>(newKey, amount);
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
