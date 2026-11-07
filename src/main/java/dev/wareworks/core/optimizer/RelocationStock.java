package dev.wareworks.core.optimizer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dev.wareworks.core.job.JobType;
import dev.wareworks.core.job.TransportJob;

/**
 * What the items a Warehouse Optimizer is moving do to the warehouse's stock figures (issue #46, ADR-079), written once
 * so the controller's numbers and their tests are the same arithmetic.
 * <p>
 * A relocation reserves its stock at the source before the pick, backing no request, and carries it outside the index
 * after it. Either way a request would see up to one trip less than the warehouse holds, so the items are counted back
 * in before availability is measured ({@link #counted}); and they are already on their way up, so they are taken off
 * what may still move ({@link #free}).
 * <p>
 * The add-back goes into the <b>count</b>, not onto the answer: {@code ReservationView#availableStock} never answers
 * below 0, and an answer clamped first and added to afterwards would hand a request the items an open request already
 * owns.
 * <p>
 * The <b>room</b> a relocation holds at its target is the other half ({@link #inbound}): before the pick its items are
 * still indexed at the source, so a figure that adds what jobs bring in to what the racks hold would count the trip
 * twice — once in the index and once as room — and a stock rule's maximum would see up to one carry too much. After the
 * pick the index no longer holds them and the room is exactly what is on its way.
 */
public final class RelocationStock {
    private RelocationStock() {
    }

    /**
     * The items of {@code key} the relocation jobs among {@code jobs} are moving: the planned amount of one that has not
     * picked yet, the held amount of one that has.
     */
    public static <K, L> long inFlight(Iterable<? extends TransportJob<K, L>> jobs, K key) {
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(key, "key");
        long moving = 0;
        for (TransportJob<K, L> job : jobs) {
            if (job.type() == JobType.RELOCATE && job.key().equals(key))
                moving += job.picked() ? job.heldAmount() : job.plannedAmount();
        }
        return moving;
    }

    /**
     * The items of {@code key} the relocation jobs among {@code jobs} have planned and not picked yet: still indexed at
     * their source, and also promised as room at their target.
     */
    public static <K, L> long planned(Iterable<? extends TransportJob<K, L>> jobs, K key) {
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(key, "key");
        long planned = 0;
        for (TransportJob<K, L> job : jobs) {
            if (job.type() == JobType.RELOCATE && !job.picked() && job.key().equals(key))
                planned += job.plannedAmount();
        }
        return planned;
    }

    /**
     * What the relocation jobs among {@code jobs} are moving, per item: {@link #inFlight} and how much of it a crane
     * already carries, outside the index ({@link Moving#carried()}). Empty while nothing is relocated, which is the
     * answer a list over every key reads once instead of one walk over the jobs per key.
     */
    public static <K, L> Map<K, Moving> byKey(Iterable<? extends TransportJob<K, L>> jobs) {
        Objects.requireNonNull(jobs, "jobs");
        Map<K, Moving> moving = new LinkedHashMap<>();
        for (TransportJob<K, L> job : jobs) {
            if (job.type() != JobType.RELOCATE)
                continue;
            Moving trip = job.picked() ? new Moving(job.heldAmount(), job.heldAmount())
                    : new Moving(job.plannedAmount(), 0L);
            moving.merge(job.key(), trip, Moving::plus);
        }
        return moving;
    }

    /**
     * What one item's relocations are moving.
     *
     * @param inFlight what {@link #inFlight} answers for it: planned before the pick, held after it
     * @param carried  the part a crane holds in its head, which the index does not count until the drop
     */
    public record Moving(long inFlight, long carried) {
        /** Nothing moving. */
        public static final Moving NONE = new Moving(0L, 0L);

        public Moving {
            inFlight = Math.max(0L, inFlight);
            carried = Math.max(0L, Math.min(carried, inFlight));
        }

        Moving plus(Moving other) {
            return new Moving(inFlight + other.inFlight, carried + other.carried);
        }
    }

    /**
     * The count availability is measured against: the indexed count with the relocated items counted as if they never
     * left. Before the pick their stock reservation takes them off again, after it the index no longer holds them, so in
     * both stages the answer is what it would be without the relocation.
     */
    public static long counted(long indexedCount, long inFlight) {
        return Math.max(0L, indexedCount) + Math.max(0L, inFlight);
    }

    /**
     * What jobs really bring in of an item: {@code reservedCapacity}, the room reserved for it
     * ({@code ReservationView#reservedCapacityFor}), without the room of the relocations that have not picked yet
     * ({@link #planned}), whose items the index still counts at their source. What a stock rule's maximum, a keeper's
     * levels and the room of a production click are measured with, so a tidy-up never makes the warehouse look fuller
     * than it is.
     */
    public static long inbound(long reservedCapacity, long planned) {
        return Math.max(0L, Math.max(0L, reservedCapacity) - Math.max(0L, planned));
    }

    /** What may still move, before a stock rule's reserve: the available stock without what is already moving. */
    public static long free(long available, long inFlight) {
        return Math.max(0L, available - Math.max(0L, inFlight));
    }
}
