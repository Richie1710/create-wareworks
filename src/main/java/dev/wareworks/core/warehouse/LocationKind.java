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
    PRODUCTION(false),
    /**
     * A warehouse stock keeper: the aisle member that holds the stock rules of the warehouse (M15, issue #3).
     * <p>
     * It is a member so that a player builds it into a rack like every other station and so that a controller finds it
     * through the ordinary membership probe, but it is <b>not</b> a station: it holds no items, exposes no item
     * capability, is never the source or the target of a transport job and is no mechanical arm target. The only thing
     * a controller reads from it is its list of rules.
     */
    KEEPER(false),
    /**
     * A warehouse home point: the rack position a crane with nothing to do waits at (M21, issue #1, ADR-034).
     * <p>
     * Like a {@link #KEEPER} it is a member without a buffer — it holds no items, exposes no item capability, is never
     * the source or the target of a transport job and is no mechanical arm target — and it is a member for the same
     * reason: a player builds it into a rack beside the rails like every other station, and the controller finds it
     * through the ordinary membership probe. The only thing read from it is <b>where it stands</b>.
     * <p>
     * <b>At most one per crane</b> ({@code docs/stacker-crane.md} §4.7): the first of a warehouse's home points in
     * {@link dev.wareworks.core.address.RackPosition#ORDER} is the one the crane uses, and every further one is
     * reported as having no effect rather than quietly ignored.
     */
    HOME(false);

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

    /**
     * Whether this kind is a <b>station</b>: a member with a buffer a crane fills or empties (input, output or
     * production station).
     * <p>
     * Deliberately no longer "everything that is not storage": a {@link #KEEPER} and a {@link #HOME} point are members
     * without any buffer, so a crane never has anything to transfer there (M15, M21).
     */
    public boolean isStation() {
        return this == INPUT || this == OUTPUT || this == PRODUCTION;
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
