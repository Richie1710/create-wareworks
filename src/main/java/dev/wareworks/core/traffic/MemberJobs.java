package dev.wareworks.core.traffic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The last job seen on each crane of a warehouse with several (issue #14, ADR-075 §9). The reservations of such a
 * warehouse are rebuilt from the jobs of all its cranes, and a crane whose chunk is away cannot be read: without this
 * its job would drop out, and another crane would be planned against its stock, its room and its share of a request.
 * So a crane that can be read is remembered as it is, and one that cannot keeps the job it was last seen with until it
 * can be read again, reports that it finished, or is no crane of the warehouse any more.
 * <p>
 * Pure Java and not thread-safe (server thread only). The content layer saves {@link #snapshot()} with the cranes and
 * restores it after a load, so a crane that is still away after a restart keeps its job too.
 *
 * @param <D> what names a crane, its dock
 * @param <J> a job
 */
public final class MemberJobs<D, J> {
    private final Map<D, J> jobs = new HashMap<>();

    /**
     * One look at every crane the warehouse lists: a crane that can be read is remembered with its job (or forgotten
     * without one), one that cannot keeps what was remembered, and a crane no longer listed is forgotten.
     *
     * @param listed  the cranes of the warehouse, in crane order
     * @param present whether a crane can be read now (its chunk ticks and it still is that crane)
     * @param jobOf   the job a crane that can be read carries now
     * @return the job of every listed crane, in crane order: read for one that can be read, remembered otherwise
     */
    public List<J> refresh(List<D> listed, Predicate<D> present, Function<D, Optional<J>> jobOf) {
        Objects.requireNonNull(listed, "listed");
        Objects.requireNonNull(present, "present");
        Objects.requireNonNull(jobOf, "jobOf");
        List<J> all = new ArrayList<>(listed.size());
        for (D crane : listed) {
            if (present.test(crane)) {
                Optional<J> job = jobOf.apply(crane);
                if (job.isPresent())
                    jobs.put(crane, job.get());
                else
                    jobs.remove(crane);
                job.ifPresent(all::add);
                continue;
            }
            J remembered = jobs.get(crane);
            if (remembered != null)
                all.add(remembered);
        }
        jobs.keySet().retainAll(new HashSet<>(listed));
        return all;
    }

    /** The remembered jobs of the listed cranes that cannot be read now, in crane order. */
    public List<J> ofAbsent(List<D> listed, Predicate<D> present) {
        Objects.requireNonNull(listed, "listed");
        Objects.requireNonNull(present, "present");
        if (jobs.isEmpty())
            return List.of();
        List<J> absent = new ArrayList<>();
        for (D crane : listed) {
            J remembered = jobs.get(crane);
            if (remembered != null && !present.test(crane))
                absent.add(remembered);
        }
        return absent;
    }

    /** {@code crane} reported {@code job}: it is what is remembered for it from now on. */
    public void remember(D crane, J job) {
        jobs.put(Objects.requireNonNull(crane, "crane"), Objects.requireNonNull(job, "job"));
    }

    /** {@code crane} finished, gave up or lost its job: nothing is remembered for it any more. */
    public void forget(D crane) {
        jobs.remove(crane);
    }

    /** Everything remembered, for the save. */
    public Map<D, J> snapshot() {
        return Map.copyOf(jobs);
    }

    /** Replaces everything remembered with what a save held. */
    public void restore(Map<D, J> saved) {
        Objects.requireNonNull(saved, "saved");
        jobs.clear();
        jobs.putAll(saved);
    }

    public void clear() {
        jobs.clear();
    }
}
