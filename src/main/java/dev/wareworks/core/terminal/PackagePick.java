package dev.wareworks.core.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import dev.wareworks.core.inventory.KeyCount;

/**
 * Which concrete boxes an <b>address order</b> fetches (issue #40, owner decision E11, design §6.3): a click on an
 * address row asks for a number of boxes, and the terminal picks that many among the boxes of the address that a
 * player may still be promised, at the click.
 * <p>
 * The order is the retrieval order of the racks (issue #47, ADR-078): the lowest storage priority first, so the boxes a
 * player parked in a "keep this" rack go last; then the keys' own value order, so the same warehouse always gives the
 * same boxes. Identical boxes share a key and are taken together, up to what the click still wants.
 * <p>
 * Pure Java; the caller resolves the candidates against its stock index.
 */
public final class PackagePick {
    private PackagePick() {
    }

    /**
     * One package key of the clicked address.
     *
     * @param key       the box key
     * @param available how many of these boxes a player may still be promised
     * @param priority  the lowest storage priority among the locations that hold it
     */
    public record Candidate<K>(K key, long available, int priority) {
        public Candidate {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * The boxes to fetch: at most {@code wanted} in all, from the candidates in retrieval order, each line at most what
     * its key has available. Empty when nothing is available or {@code wanted < 1}.
     */
    public static <K> List<KeyCount<K>> pick(List<Candidate<K>> candidates, int wanted, Comparator<? super K> order) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(order, "order");
        if (wanted < 1 || candidates.isEmpty())
            return List.of();
        List<Candidate<K>> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt((Candidate<K> candidate) -> candidate.priority())
                .thenComparing(Candidate::key, order));
        List<KeyCount<K>> picked = new ArrayList<>();
        long left = wanted;
        for (Candidate<K> candidate : sorted) {
            if (left <= 0L)
                break;
            long take = Math.min(left, candidate.available());
            if (take <= 0L)
                continue;
            picked.add(new KeyCount<>(candidate.key(), take));
            left -= take;
        }
        return List.copyOf(picked);
    }
}
