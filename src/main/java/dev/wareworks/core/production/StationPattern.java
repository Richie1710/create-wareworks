package dev.wareworks.core.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One pattern of one production station of an aisle — a {@link ProductionPattern} together with the station that holds
 * it ({@code docs/warehouse-system.md} §3.5, ADR-024).
 * <p>
 * A pattern on its own says what a machine turns into what; a <b>plan</b> also has to say <i>which</i> machine, because
 * every node of a plan becomes a production order at one station and the crane has to drive there
 * ({@link ProductionPlanner}). This is the pure-Java counterpart of the controller's own
 * {@code WarehouseControllerBlockEntity.AislePattern}, which is exactly this pair for {@code ItemKey} and
 * {@code RackPosition}.
 * <p>
 * The <b>order of a list of these is the aisle order</b> the planner resolves ties by: the stations in the order the
 * controller records them, and within a station the patterns in authoring order. That is what makes a plan
 * reproducible — the same warehouse always picks the same pattern for the same item.
 *
 * @param station where the ingredients go and the result is expected from
 * @param pattern what one run of that station's machine is expected to do
 * @param <K>     item key type
 * @param <L>     location type
 */
public record StationPattern<K, L>(L station, ProductionPattern<K> pattern) {
    public StationPattern {
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(pattern, "pattern");
    }

    /** The pair of {@code station} and {@code pattern}. */
    public static <K, L> StationPattern<K, L> of(L station, ProductionPattern<K> pattern) {
        return new StationPattern<>(station, pattern);
    }

    /**
     * The same patterns at one station, in authoring order — the shape a test or a caller with a single machine needs.
     */
    public static <K, L> List<StationPattern<K, L>> at(L station, List<ProductionPattern<K>> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        List<StationPattern<K, L>> found = new ArrayList<>(patterns.size());
        for (ProductionPattern<K> pattern : patterns)
            found.add(new StationPattern<>(station, pattern));
        return List.copyOf(found);
    }

    /** Whether one run of this station's pattern yields {@code key}. */
    public boolean produces(K key) {
        return pattern.produces(key);
    }

    /** What one run yields. */
    public K result() {
        return pattern.result().key();
    }
}
