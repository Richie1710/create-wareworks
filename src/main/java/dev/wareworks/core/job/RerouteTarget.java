package dev.wareworks.core.job;

import java.util.Objects;

import dev.wareworks.core.warehouse.LocationKind;

/**
 * A new target for items left in a handling head ({@link JobPlanner#planReroute}, {@code docs/warehouse-system.md} §8).
 *
 * @param location    the new target
 * @param kind        its kind
 * @param amount      how many of the held items it accepted in the live simulation (at least 1; it may be fewer than
 *                    held, the rest is rerouted again after the drop)
 * @param travelTicks travel time from the crane's position
 * @param <L>         location type
 */
public record RerouteTarget<L>(L location, LocationKind kind, int amount, long travelTicks) {
    public RerouteTarget {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(kind, "kind");
        if (amount < 1)
            throw new IllegalArgumentException("amount must be at least 1: " + amount);
        if (travelTicks < 0)
            throw new IllegalArgumentException("travelTicks must not be negative: " + travelTicks);
    }
}
