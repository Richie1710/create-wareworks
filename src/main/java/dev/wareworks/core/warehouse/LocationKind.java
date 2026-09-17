package dev.wareworks.core.warehouse;

import java.util.Optional;

/**
 * What a member block at a rack position provides to its aisle ({@code docs/warehouse-system.md} §1, §3).
 * <p>
 * The facing rule differs per kind: a storage location (warehouse interface) faces away from the aisle towards its
 * inventory, stations (warehouse input and output) face the aisle. {@link #name()} is the stable save name.
 */
public enum LocationKind {
    /** A warehouse interface in front of an inventory: an addressable storage location. */
    STORAGE(true),
    /** A warehouse input station: items to store. */
    INPUT(false),
    /** A warehouse output station: retrieved items are dropped here. */
    OUTPUT(false),
    /**
     * A warehouse production station: the crane delivers the ingredients of a production order here and the player's
     * machinery takes them out ({@code docs/warehouse-system.md} §3.5, ADR-024).
     * <p>
     * It is a delivery target like {@link #OUTPUT}, but deliberately <b>not</b> the same kind: a production station is
     * never the destination of a retrieval request, and retrieve leftovers must never be dumped into one, which is
     * exactly what reusing {@code OUTPUT} would allow (ADR-024).
     */
    PRODUCTION(false);

    private final boolean facesAwayFromAisle;

    LocationKind(boolean facesAwayFromAisle) {
        this.facesAwayFromAisle = facesAwayFromAisle;
    }

    /**
     * Whether an aligned member of this kind faces away from the aisle ({@code FACING == side}); otherwise it faces the
     * aisle ({@code FACING == side.getOpposite()}).
     */
    public boolean facesAwayFromAisle() {
        return facesAwayFromAisle;
    }

    /** Whether this kind is a station (input, output or production). */
    public boolean isStation() {
        return this != STORAGE;
    }

    /**
     * Whether a crane <b>delivers</b> items to this kind on behalf of something that asked for them: a retrieval
     * request at an {@link #OUTPUT}, a production order at a {@link #PRODUCTION} station. Items on their way to such a
     * location are counted as in transit rather than as reserved capacity, because they have already left the indexed
     * stock but are still owed to whoever asked ({@code ReservationLedger#reservationsFor},
     * {@code docs/warehouse-system.md} §7.4).
     */
    public boolean isDeliveryTarget() {
        return this == OUTPUT || this == PRODUCTION;
    }

    /** The kind with the given save name, or empty for {@code null} or unknown names. */
    public static Optional<LocationKind> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (LocationKind kind : values()) {
            if (kind.name().equals(name))
                return Optional.of(kind);
        }
        return Optional.empty();
    }
}
