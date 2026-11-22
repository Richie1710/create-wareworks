package dev.wareworks.content.controller;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Package mode (issue #40, review fix 2026-11-22): boxes of an address order that reached a package door while the
 * terminal holding the order was not loaded. The controller keeps each such delivery here until a terminal's pass
 * claims it, so that pass does not forget the finished request and ask for the line again (one extra box out of the
 * door). Bounded: at most {@value #MAX_ENTRIES} entries, each kept for at most {@value #MAX_AGE_TICKS} ticks; a
 * delivery nobody ever claims (an ordinary redstone request at a door) simply ages out. Not saved: after a restart the
 * terminal loads with the controller's chunk area and the request is still open or long credited.
 */
public final class PendingDoorCredits {
    /** Upper bound of remembered deliveries; the oldest goes first. */
    static final int MAX_ENTRIES = 256;
    /** How long a delivery is kept for a terminal to claim it: five minutes of game time. */
    static final long MAX_AGE_TICKS = 6000L;

    /** A terminal's claim: whether its address order took {@code amount} boxes of request {@code id}. */
    @FunctionalInterface
    public interface Claim {
        boolean take(UUID id, int amount);
    }

    private record Entry(int amount, long at) {
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /** Remembers {@code amount} boxes of request {@code id} delivered at {@code now}. */
    void remember(UUID id, int amount, long now) {
        Objects.requireNonNull(id, "id");
        if (amount < 1)
            return;
        Entry before = entries.remove(id);
        entries.put(id, new Entry(amount + (before == null ? 0 : before.amount()), now));
        while (entries.size() > MAX_ENTRIES)
            entries.remove(entries.keySet().iterator().next());
    }

    /** Offers every kept delivery to {@code claim}; a claimed one and a too old one are forgotten. */
    void claim(Claim claim, long now) {
        Objects.requireNonNull(claim, "claim");
        for (Iterator<Map.Entry<UUID, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Entry> entry = it.next();
            if (now - entry.getValue().at() > MAX_AGE_TICKS || now < entry.getValue().at()
                    || claim.take(entry.getKey(), entry.getValue().amount()))
                it.remove();
        }
    }

    /** Whether nothing is kept. */
    boolean isEmpty() {
        return entries.isEmpty();
    }
}
