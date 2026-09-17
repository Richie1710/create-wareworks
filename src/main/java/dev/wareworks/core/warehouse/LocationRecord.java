package dev.wareworks.core.warehouse;

import java.util.Objects;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;

/**
 * One member of an aisle: a rack position and what the block there provides ({@code docs/warehouse-system.md} §3.3).
 * The rack position is the location id: it is aisle-local, so it survives restarts and never depends on world
 * coordinates.
 *
 * @param position aisle-local rack position (x, y, side)
 * @param kind     storage location or station
 */
public record LocationRecord(RackPosition position, LocationKind kind) {
    public LocationRecord {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(kind, "kind");
    }

    public static LocationRecord of(RackPosition position, LocationKind kind) {
        return new LocationRecord(position, kind);
    }

    public int x() {
        return position.x();
    }

    public int y() {
        return position.y();
    }

    public Side side() {
        return position.side();
    }
}
