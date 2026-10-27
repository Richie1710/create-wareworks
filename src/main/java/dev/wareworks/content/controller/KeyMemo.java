package dev.wareworks.content.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A remembered yes-or-no answer per key, for a question whose answer never changes for a key (M31, issue #21, ADR-073):
 * whether an item key is an empty fluid container ({@code FillRecipes#isEmptyContainer}).
 * <p>
 * <b>Sized to the keys it is asked about.</b> The terminal asks it of every item type in stock on every push, so a
 * bound below the number of item types would be cleared in the middle of every pass and answer almost nothing from
 * memory. The caller names how many keys a pass asks about ({@link #expect}); the bound is twice that, and never below
 * {@link #minimumBound}, so one pass over the index fits with room for the keys of other questions, and the map is
 * cleared at most once while the index grows instead of once per pass. Keys that left the index are forgotten at that
 * clear.
 * <p>
 * Pure Java, no Minecraft types: JUnit-testable with any key. Server thread only.
 *
 * @param <K> the key
 */
final class KeyMemo<K> {
    private final Predicate<? super K> probe;
    private final int minimumBound;
    private final Map<K, Boolean> answers = new HashMap<>();
    private int bound;

    /**
     * @param probe        the question, asked once per key until the memo is cleared
     * @param minimumBound smallest number of answers kept before the memo is cleared, at least 1
     * @throws IllegalArgumentException if {@code minimumBound < 1}
     */
    KeyMemo(Predicate<? super K> probe, int minimumBound) {
        this.probe = Objects.requireNonNull(probe, "probe");
        if (minimumBound < 1)
            throw new IllegalArgumentException("minimumBound must be at least 1: " + minimumBound);
        this.minimumBound = minimumBound;
        this.bound = minimumBound;
    }

    /**
     * Sizes the memo for a pass over {@code keys} distinct keys: from now on it keeps up to twice that many answers (at
     * least {@link #minimumBound}). Never shrinks below what it already holds, so nothing is forgotten by sizing.
     */
    void expect(int keys) {
        long wanted = Math.max((long) minimumBound, 2L * Math.max(0, keys));
        bound = (int) Math.min(Integer.MAX_VALUE, Math.max(wanted, bound));
    }

    /** The answer for {@code key}: remembered, or asked of the probe once and then remembered. */
    boolean test(K key) {
        Objects.requireNonNull(key, "key");
        Boolean answer = answers.get(key);
        if (answer != null)
            return answer;
        answer = probe.test(key);
        if (answers.size() >= bound)
            answers.clear();
        answers.put(key, answer);
        return answer;
    }

    /** How many answers are remembered right now. */
    int size() {
        return answers.size();
    }

    /** Forgets every answer and returns to the smallest bound. */
    void clear() {
        answers.clear();
        bound = minimumBound;
    }
}
