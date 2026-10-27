package dev.wareworks.core.computer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which item a computer meant (issue #9, ADR-074, {@code docs/computercraft.md}): the decision over the keys a terminal
 * lists, kept free of Minecraft so it can be tested on its own.
 * <p>
 * A computer names an item by its id and, when the warehouse holds several variants of it (an enchanted book, a named
 * sword), by the fingerprint the stock list gave it. In this order:
 * <ol>
 * <li>a fingerprint was given: the candidate with that id and fingerprint, or {@link Absent} (a refusal, not an
 * error: the variant may simply have run out);</li>
 * <li>only the id: the candidate of that id without components; else the only variant if there is exactly one; else
 * {@link Ambiguous} with the number of variants (an error the computer has to fix); none at all: {@link Absent}.</li>
 * </ol>
 * Fingerprints compare without regard to case.
 *
 * @param <K> the key type
 */
public sealed interface ItemChoice<K> {
    /** The one key the computer meant. */
    record Found<K>(K key) implements ItemChoice<K> {
        public Found {
            Objects.requireNonNull(key, "key");
        }
    }

    /** No listed key matches. */
    record Absent<K>() implements ItemChoice<K> {
    }

    /** The id alone names {@code variants} keys, none of them without components. */
    record Ambiguous<K>(int variants) implements ItemChoice<K> {
    }

    /**
     * One listed key as the choice sees it.
     *
     * @param id            the item id, e.g. {@code minecraft:oak_log}
     * @param hasComponents whether the key carries components beyond its item's defaults
     */
    record Candidate<K>(K key, String id, boolean hasComponents) {
        public Candidate {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * The key {@code id} and {@code fingerprint} name among {@code candidates}.
     *
     * @param fingerprint the fingerprint the computer gave, if any; only computed for candidates of {@code id}
     * @param fingerprints the fingerprint of a key
     */
    static <K> ItemChoice<K> choose(List<Candidate<K>> candidates, String id, Optional<String> fingerprint,
            Function<K, String> fingerprints) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(fingerprints, "fingerprints");
        List<Candidate<K>> sameItem = new ArrayList<>();
        for (Candidate<K> candidate : candidates) {
            if (candidate.id().equals(id))
                sameItem.add(candidate);
        }
        if (fingerprint.isPresent()) {
            String wanted = fingerprint.get().toLowerCase(Locale.ROOT);
            for (Candidate<K> candidate : sameItem) {
                if (fingerprints.apply(candidate.key()).toLowerCase(Locale.ROOT).equals(wanted))
                    return new Found<>(candidate.key());
            }
            return new Absent<>();
        }
        for (Candidate<K> candidate : sameItem) {
            if (!candidate.hasComponents())
                return new Found<>(candidate.key());
        }
        if (sameItem.isEmpty())
            return new Absent<>();
        if (sameItem.size() == 1)
            return new Found<>(sameItem.get(0).key());
        return new Ambiguous<>(sameItem.size());
    }
}
