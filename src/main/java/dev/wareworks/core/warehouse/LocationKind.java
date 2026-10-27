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
    STORAGE(true, LocationKind.FULL_STROKE),
    /** A warehouse input station: items to store. */
    INPUT(false, LocationKind.FULL_STROKE),
    /**
     * A warehouse output station: retrieved items are dropped here. The warehouse terminal and every port mode
     * (requesting, accepting, collecting) are of this kind.
     */
    OUTPUT(false, LocationKind.STATION_STROKE),
    /**
     * A warehouse production station: the crane delivers the ingredients of a production order here and the player's
     * machinery takes them out ({@code docs/warehouse-system.md} §3.5, ADR-024).
     * <p>
     * It is a delivery target like {@link #OUTPUT}, but deliberately <b>not</b> the same kind: a production station is
     * never the destination of a retrieval request, and retrieve leftovers must never be dumped into one, which is
     * exactly what reusing {@code OUTPUT} would allow (ADR-024).
     */
    PRODUCTION(false, LocationKind.STATION_STROKE),
    /**
     * A warehouse stock keeper: the aisle member that holds the stock rules of the warehouse (M15, issue #3).
     * <p>
     * It is a member so that a player builds it into a rack like every other station and so that a controller finds it
     * through the ordinary membership probe, but it is <b>not</b> a station: it holds no items, exposes no item
     * capability, is never the source or the target of a transport job and is no mechanical arm target. The only thing
     * a controller reads from it is its list of rules.
     */
    KEEPER(false, LocationKind.FULL_STROKE),
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
    HOME(false, LocationKind.FULL_STROKE);

    /**
     * The arm's full stroke: the grabber reaches one whole block into the member. It equals
     * {@code TravelTimeModel.FULL_EXTENSION} and {@code CranePose.EXTENDED} (pinned by JUnit; a literal here, because
     * this package must not depend on {@code core.job} or {@code core.crane}).
     */
    public static final double FULL_STROKE = 1.0;
    /**
     * The arm's stroke at a station whose model is solid behind its aisle opening (issue #39, ADR-072): half a block.
     * <p>
     * At the full stroke the front of the grabber ends 15.5 px into the member. The warehouse terminal's screen (or its
     * plain back panel) sits at 15 px, so the fork tip poked 0.5 px through it, and in a warehouse output or production
     * station the grabber stood in the open port on the far side. At half a block the grabber front stops at 7.5 px, in
     * the middle of the solid body, and the first carried item rests in the aisle-side opening. The upper bound for this
     * value is 13/16, where the grabber front is 0.5 px before the back of the solid body; {@code CraneModelLayoutTest}
     * pins it.
     */
    public static final double STATION_STROKE = 0.5;

    private final boolean facesAwayFromAisle;
    private final double armStroke;

    LocationKind(boolean facesAwayFromAisle, double armStroke) {
        this.facesAwayFromAisle = facesAwayFromAisle;
        this.armStroke = armStroke;
    }

    /**
     * How far the crane's arm extends to pick or drop at a member of this kind, in the unit of {@code CranePose#arm}:
     * a fraction of the arm's full reach of one block (ADR-072).
     * <p>
     * Storage (interfaces in front of inventories, rack bays, fluid bays) and the warehouse input take the
     * {@link #FULL_STROKE}: their models are built for the full reach, and the inventory or the input's body covers the
     * grabber. The terminal, every warehouse output and the production station take the {@link #STATION_STROKE}, so the
     * fork stays inside their solid body. A {@link #KEEPER} and a {@link #HOME} point are never the source or the target
     * of a job, so their value is never used.
     */
    public double armStroke() {
        return armStroke;
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
