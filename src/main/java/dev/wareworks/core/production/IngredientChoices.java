package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What may pay for a choice ingredient of a production pattern (ADR-082): the content layer's answer, built once per
 * click or planning pass.
 * <p>
 * <b>The core never matches a filter.</b> A choice ingredient's key is a Create List or Attribute Filter
 * ({@link ProductionPattern#choices()}), and only the content layer can ask Create what such a filter lets through.
 * This interface is the boundary: it hands the planners a bounded, stable list of concrete keys per choice, and the
 * planners pay the choice out of those keys ({@link ChoiceAllocator}). Nothing here is asked per tick; the content side
 * caches what it answers ({@code FilterMatchCache}).
 * <p>
 * Pure Java with no Minecraft types.
 *
 * @param <K> item key type
 */
public interface IngredientChoices<K> {
    /**
     * Keys that may pay for {@code choice} of {@code pattern}: the stock matches and the producible results the filter
     * allows, each once, in a stable order ({@code ItemKey.ORDER} on the content side), never the pattern's own result,
     * and bounded ({@code AisleChoices}: at most 64 stock keys plus 16 producible results).
     *
     * @param pattern one of the pass's own pattern objects; the content side recognises it by identity to narrow the
     *                candidates to what a Crafter at that station takes. An unknown pattern gets un-narrowed candidates
     * @param choice  one of {@code pattern}'s choice keys
     */
    List<K> candidates(ProductionPattern<K> pattern, K choice);

    /**
     * Free stock of {@code key}: what is available above <b>every</b> reserve (the AUTOMATION availability), the second
     * rank of the pick order ({@link ChoiceAllocator}). {@link Long#MAX_VALUE} means "no reserve information", so the
     * access's own availability alone decides.
     */
    default long free(K key) {
        return Long.MAX_VALUE;
    }

    /**
     * Whether {@code key} carries no component changes: such keys are picked before enchanted, damaged or named ones, so a
     * broad filter keeps the special items longest (ADR-082 D4, the ADR-080 rule).
     */
    default boolean plain(K key) {
        return true;
    }

    /**
     * These choices without {@code keys} among any choice's candidates; free stock and plainness unchanged. What
     * automatic restocking pays a filter cell with while other rules call for some of its kinds: a kind another rule is
     * short of is left out of this decision, and the rest of the filter still pays (ADR-082).
     *
     * @return this instance when {@code keys} is empty
     */
    default IngredientChoices<K> excluding(Set<? extends K> keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty())
            return this;
        IngredientChoices<K> all = this;
        return new IngredientChoices<>() {
            @Override
            public List<K> candidates(ProductionPattern<K> pattern, K choice) {
                List<K> candidates = all.candidates(pattern, choice);
                List<K> kept = new ArrayList<>(candidates.size());
                for (K candidate : candidates) {
                    if (!keys.contains(candidate))
                        kept.add(candidate);
                }
                return kept;
            }

            @Override
            public long free(K key) {
                return all.free(key);
            }

            @Override
            public boolean plain(K key) {
                return all.plain(key);
            }
        };
    }

    /** No candidates for any choice: a pattern with choices makes nothing. What every caller without filters passes. */
    static <K> IngredientChoices<K> none() {
        return (pattern, choice) -> List.of();
    }

    /** Fixed candidate lists per choice key, every key plain and without reserve information; for tests. */
    static <K> IngredientChoices<K> of(Map<K, List<K>> byChoice) {
        Objects.requireNonNull(byChoice, "byChoice");
        Map<K, List<K>> copy = new LinkedHashMap<>();
        byChoice.forEach((choice, keys) -> copy.put(choice, List.copyOf(keys)));
        return (pattern, choice) -> copy.getOrDefault(choice, List.of());
    }
}
