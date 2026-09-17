package dev.wareworks.content.crane.head;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.wareworks.content.item.ItemKey;

/**
 * What a handling head carries ({@code docs/stacker-crane.md} §6): item keys with amounts, each key at most once, in the
 * order they were picked. Immutable; a head returns a new instance from {@link HandlingHead#held()} after every change.
 * <p>
 * Normally a head holds one key (the job's); a second key only appears when a misbehaving inventory hands out something
 * else and it cannot be given back, which the crane then spills (never deletes).
 *
 * @param entries held stacks; keys are distinct and every count is at least 1
 */
public record HeldItems(List<Entry> entries) {
    /** Nothing held. */
    public static final HeldItems EMPTY = new HeldItems(List.of());

    /**
     * One held item key.
     *
     * @param key   exact item identity
     * @param count amount, at least 1 (may exceed a stack; the head splits when inserting or dropping)
     */
    public record Entry(ItemKey key, int count) {
        public Entry {
            Objects.requireNonNull(key, "key");
            if (count < 1)
                throw new IllegalArgumentException("count must be at least 1: " + count);
        }
    }

    public HeldItems {
        entries = List.copyOf(entries);
        Set<ItemKey> seen = new HashSet<>();
        for (Entry entry : entries) {
            if (!seen.add(entry.key()))
                throw new IllegalArgumentException("duplicate held key " + entry.key());
        }
    }

    /** Held amount of {@code key}, 0 if none. */
    public int count(ItemKey key) {
        Objects.requireNonNull(key, "key");
        for (Entry entry : entries) {
            if (entry.key().equals(key))
                return entry.count();
        }
        return 0;
    }

    /** Sum of all held amounts. */
    public long totalCount() {
        long total = 0;
        for (Entry entry : entries)
            total += entry.count();
        return total;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
