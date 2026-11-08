package dev.wareworks.core.requester;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Which stored variant of an item a Schematicannon Requester orders for a <b>plain</b> requirement (issue #45, ADR-080).
 * <p>
 * A plain requirement takes any variant of the item (Create's {@code StackRequirement#matches} compares the item only),
 * so the requester is free to choose, and it chooses so that named, enchanted or otherwise special stock stays where it
 * is:
 * <ol>
 * <li>the variant <b>without components</b>, when automation may take some of it;</li>
 * <li>otherwise the variant with the <b>most</b> available to automation;</li>
 * <li>on a tie the one that comes first by {@code tieBreak} (the item key's stable order, never a hash).</li>
 * </ol>
 * Variants of which nothing is available are never picked; with none left the answer is empty. Strict requirements
 * never come here: they are ordered with exactly the components the cannon reported.
 * <p>
 * <b>To have one made</b> ({@link #toMake}, ADR-081) is the second question, asked only when automation may take none
 * of any variant: the plain variant when a production pattern makes it, otherwise the first a pattern makes by
 * {@code tieBreak}. A stocked variant is always ordered first; the controller makes the rest of it only when a pattern
 * makes exactly that key.
 */
public final class VariantPick {
    private VariantPick() {
    }

    /**
     * The variant to have <b>made</b> when automation may take none from stock: the plain one if a pattern makes it,
     * else the first by {@code tieBreak}; empty when no pattern makes the item.
     *
     * @param producible the keys the aisle's production patterns make
     * @param sameItem   whether a key is a variant of the missing item
     * @param plain      whether a key has no component changes
     * @param tieBreak   the stable order that decides among variants with components
     * @param <K>        item key type
     */
    public static <K> Optional<K> toMake(Iterable<K> producible, Predicate<? super K> sameItem,
            Predicate<? super K> plain, Comparator<? super K> tieBreak) {
        Objects.requireNonNull(producible, "producible");
        Objects.requireNonNull(sameItem, "sameItem");
        Objects.requireNonNull(plain, "plain");
        Objects.requireNonNull(tieBreak, "tieBreak");
        K best = null;
        for (K key : producible) {
            if (key == null || !sameItem.test(key))
                continue;
            if (plain.test(key))
                return Optional.of(key);
            if (best == null || tieBreak.compare(key, best) < 0)
                best = key;
        }
        return Optional.ofNullable(best);
    }

    /**
     * The variant to order among {@code keys} (the stock index's keys, a walk over all of them).
     *
     * @param keys      the candidates; only those {@code sameItem} accepts are looked at
     * @param sameItem  whether a key is a variant of the missing item
     * @param plain     whether a key has no component changes
     * @param available how many of a key automation may take
     * @param tieBreak  the stable order that decides a tie
     * @param <K>       item key type
     */
    public static <K> Optional<K> pick(Iterable<K> keys, Predicate<? super K> sameItem, Predicate<? super K> plain,
            ToLongFunction<? super K> available, Comparator<? super K> tieBreak) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(sameItem, "sameItem");
        Objects.requireNonNull(plain, "plain");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(tieBreak, "tieBreak");
        K best = null;
        long bestCount = 0;
        for (K key : keys) {
            if (key == null || !sameItem.test(key))
                continue;
            long count = available.applyAsLong(key);
            if (count <= 0)
                continue;
            if (plain.test(key))
                return Optional.of(key);
            if (best == null || count > bestCount || count == bestCount && tieBreak.compare(key, best) < 0) {
                best = key;
                bestCount = count;
            }
        }
        return Optional.ofNullable(best);
    }
}
