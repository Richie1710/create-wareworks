package dev.wareworks.core.inventory;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;

/**
 * Locations waiting for a fresh snapshot ({@code docs/warehouse-system.md} §5): de-duplicated, first in first out, with
 * two priorities.
 * <ul>
 * <li><b>Urgent</b>: something is known to have changed (a content hint, a location that joined, a location that took
 * over another one's counts). Polled first.</li>
 * <li><b>Background</b>: counts that are only unverified (restored from a save). Polled when nothing is urgent.</li>
 * </ul>
 * A location is queued at most once: adding it again keeps its place, adding a background location as urgent moves it
 * to the urgent end. The owner drains a bounded number of locations per tick, so a burst of changes (a rebuilt aisle,
 * a hopper line feeding many chests) spreads over several ticks instead of reading every inventory in one tick.
 * <p>
 * Pure Java, not thread-safe (server thread only).
 *
 * @param <L> location type
 */
public final class SnapshotQueue<L> {
    private final LinkedHashSet<L> urgent = new LinkedHashSet<>();
    private final LinkedHashSet<L> background = new LinkedHashSet<>();

    /**
     * Queues {@code location} with priority; a background entry for it is promoted.
     *
     * @return whether it was not queued as urgent before
     */
    public boolean addUrgent(L location) {
        Objects.requireNonNull(location, "location");
        background.remove(location);
        return urgent.add(location);
    }

    /**
     * Queues {@code location} for background verification unless it is already queued.
     *
     * @return whether it was not queued before
     */
    public boolean addBackground(L location) {
        Objects.requireNonNull(location, "location");
        if (urgent.contains(location))
            return false;
        return background.add(location);
    }

    /** Removes {@code location} from the queue; returns whether it was queued. */
    public boolean remove(L location) {
        Objects.requireNonNull(location, "location");
        boolean wasUrgent = urgent.remove(location);
        boolean wasBackground = background.remove(location);
        return wasUrgent || wasBackground;
    }

    /** Takes the next location: the oldest urgent one, otherwise the oldest background one. */
    public Optional<L> poll() {
        Optional<L> next = pollFrom(urgent);
        return next.isPresent() ? next : pollFrom(background);
    }

    private static <L> Optional<L> pollFrom(LinkedHashSet<L> set) {
        Iterator<L> iterator = set.iterator();
        if (!iterator.hasNext())
            return Optional.empty();
        L next = iterator.next();
        iterator.remove();
        return Optional.of(next);
    }

    public boolean contains(L location) {
        Objects.requireNonNull(location, "location");
        return urgent.contains(location) || background.contains(location);
    }

    public boolean isUrgent(L location) {
        return urgent.contains(Objects.requireNonNull(location, "location"));
    }

    public int size() {
        return urgent.size() + background.size();
    }

    public int urgentCount() {
        return urgent.size();
    }

    public boolean isEmpty() {
        return urgent.isEmpty() && background.isEmpty();
    }

    public void clear() {
        urgent.clear();
        background.clear();
    }

    @Override
    public String toString() {
        return "SnapshotQueue[urgent=" + urgent.size() + ", background=" + background.size() + "]";
    }
}
