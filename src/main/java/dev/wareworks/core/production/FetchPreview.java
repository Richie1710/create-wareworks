package dev.wareworks.core.production;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the lines of a craft fetch would meet, one after another in line order, against one snapshot of the warehouse
 * ({@link SequentialPlans#preview(ProductionPlanInput, java.util.SequencedMap)}, {@code docs/warehouse-system.md}
 * §3.4.5, issue #24, slice 3): per line, what it takes straight out of the racks at its own turn, and for a line the
 * racks could not serve in full, the chain previewed for the rest.
 * <p>
 * It is the shape of the craft order's portions: each one claims what the racks still hold of its item once every
 * earlier line and chain has taken its share, and has only the rest made. A measurement and nothing else — no order,
 * no promise — thrown away after the Fetch it was measured for.
 *
 * @param taken  per line, in line order, the items it takes straight out of the racks at its turn: what the snapshot
 *               still held of its item after every earlier line's take and every earlier chain's spend, at most what
 *               the line needs. Unmodifiable
 * @param chains per line the racks could not serve in full, in line order, the preview of the chain that would make
 *               the rest. A line without one was served by the racks alone. Unmodifiable
 * @param <K>    item key type
 */
public record FetchPreview<K>(Map<K, Long> taken, Map<K, ChainPreview<K>> chains) {
    public FetchPreview {
        taken = copy(taken);
        chains = copy(chains);
    }

    /** No line previewed: what a fetch nothing is short of, or a warehouse without production, answers. */
    public static <K> FetchPreview<K> none() {
        return new FetchPreview<>(Map.of(), Map.of());
    }

    private static <K, V> Map<K, V> copy(Map<K, V> map) {
        if (map == null || map.isEmpty())
            return Map.of();
        Map<K, V> copy = new LinkedHashMap<>(map.size());
        map.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value,
                "value")));
        return Collections.unmodifiableMap(copy);
    }
}
