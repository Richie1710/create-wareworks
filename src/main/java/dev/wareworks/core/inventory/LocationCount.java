package dev.wareworks.core.inventory;

import java.util.Objects;

/**
 * A location together with the stored amount of one item key there, e.g. one entry of
 * {@link StockIndex#locationsOf(Object)}.
 *
 * @param location the storage location, never {@code null}
 * @param count    the stored amount, always positive
 * @param <L>      the location type
 */
public record LocationCount<L>(L location, long count) {
    public LocationCount {
        Objects.requireNonNull(location, "location");
        if (count <= 0)
            throw new IllegalArgumentException("count must be positive: " + count);
    }
}
