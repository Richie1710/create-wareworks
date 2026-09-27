package dev.wareworks.core.job;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.wareworks.core.warehouse.LocationKind;

/**
 * What a {@link TransportJob} does ({@code docs/warehouse-system.md} §7). {@link #name()} is the stable save name.
 * <p>
 * The kinds of the job's endpoints follow from the type: a store job picks at an input station and drops at a storage
 * location, a retrieve job picks at a storage location and drops at an output station. Leftovers in the handling head
 * can be rerouted (§8), so a job's target may later be another kind: store leftovers go back to an input buffer,
 * retrieve leftovers into a storage location.
 * <p>
 * <b>A store job may also drop at an {@link LocationKind#OUTPUT}</b> (M17, issue #12): an accepting warehouse port is a
 * target the store plan may choose and the last resort for store leftovers. The allowed target kinds are therefore a
 * <b>set</b> rather than the two named kinds — {@link #plannedTargetKind()} and {@link #fallbackTargetKind()} keep their
 * meaning for their existing readers, but they no longer enumerate everything a type may drop at.
 * <p>
 * <b>A collect job picks at an {@link LocationKind#OUTPUT}</b> (M18, issue #13): a collecting warehouse port is the one
 * output station a crane takes items <i>out of</i>, and it is never a collect job's target ({@link #COLLECT}).
 */
public enum JobType {
    /** Input station → storage location, an accepting warehouse port, or back into an input buffer. */
    STORE(LocationKind.INPUT, LocationKind.STORAGE, LocationKind.INPUT,
            Set.of(LocationKind.STORAGE, LocationKind.INPUT, LocationKind.OUTPUT)),
    /** Storage location → output station. */
    RETRIEVE(LocationKind.STORAGE, LocationKind.OUTPUT, LocationKind.STORAGE,
            Set.of(LocationKind.OUTPUT, LocationKind.STORAGE)),
    /**
     * Storage location → production station ({@code docs/warehouse-system.md} §3.5, ADR-024): one ingredient of a
     * production order on its way to the machine that will consume it.
     * <p>
     * It is a {@code RETRIEVE} in everything that matters to the warehouse — it takes items out of storage and
     * reserves them there — and differs only in where they go and in who is waiting for them. Leftovers go back into
     * storage, never to an output: nobody requested them at a station.
     */
    SUPPLY(LocationKind.STORAGE, LocationKind.PRODUCTION, LocationKind.STORAGE,
            Set.of(LocationKind.PRODUCTION, LocationKind.STORAGE)),
    /**
     * A <b>collecting</b> warehouse port → storage location, or back into an input buffer ({@code
     * docs/warehouse-system.md} §3.2.4, M18, issue #13): items the crane fetched out of the inventory a player pointed
     * the port at, on their way into the racks like anything that arrived at an input.
     * <p>
     * Its source is an {@link LocationKind#OUTPUT} — a warehouse port — and its allowed targets deliberately <b>exclude
     * {@code OUTPUT}</b>, so {@code TransportJob}'s constructor refuses a collect job that would drop at a port. That is
     * the loop answer made structural rather than a rule: collect → store → maximum → overflow → collect cannot close,
     * because a collected item can never leave through a port. It is added rather than folded into {@code STORE} for
     * exactly the reason {@code SUPPLY} was added rather than folded into {@code RETRIEVE} (ADR-024): the leftovers of
     * one must not be droppable where the leftovers of the other may go.
     */
    COLLECT(LocationKind.OUTPUT, LocationKind.STORAGE, LocationKind.INPUT,
            Set.of(LocationKind.STORAGE, LocationKind.INPUT));

    private final LocationKind sourceKind;
    private final LocationKind plannedTargetKind;
    private final LocationKind fallbackTargetKind;
    private final Set<LocationKind> allowedTargets;

    JobType(LocationKind sourceKind, LocationKind plannedTargetKind, LocationKind fallbackTargetKind,
            Set<LocationKind> allowedTargets) {
        this.sourceKind = sourceKind;
        this.plannedTargetKind = plannedTargetKind;
        this.fallbackTargetKind = fallbackTargetKind;
        this.allowedTargets = allowedTargets;
    }

    /** The kind of location the items are picked from. */
    public LocationKind sourceKind() {
        return sourceKind;
    }

    /** The kind of location a newly planned job drops at. */
    public LocationKind plannedTargetKind() {
        return plannedTargetKind;
    }

    /** The second choice for rerouted leftovers, after other locations of {@link #plannedTargetKind()} (§8). */
    public LocationKind fallbackTargetKind() {
        return fallbackTargetKind;
    }

    /**
     * Whether a job of this type takes items <b>out of storage</b>, so that before the pick it reserves stock at its
     * source instead of capacity at its target ({@code ReservationLedger#reservationsFor}). True for
     * {@link #RETRIEVE} and {@link #SUPPLY}.
     */
    public boolean reservesSourceStock() {
        return sourceKind == LocationKind.STORAGE;
    }

    /**
     * Whether a job of this type may name who is waiting for its items ({@code TransportJob#requestId()}): a
     * {@link #RETRIEVE} names the retrieval request it serves, a {@link #SUPPLY} the ingredient line of the production
     * order it serves. A {@link #STORE} and a {@link #COLLECT} job never have one — nothing asked for those items, they
     * simply arrived or were fetched.
     * <p>
     * Written as the two types that <b>do</b> carry one rather than as "not a store" (M18): the list of types that serve
     * somebody is the shorter and the more stable one, and adding a type must not silently make it a request carrier.
     */
    public boolean mayCarryRequestId() {
        return this == RETRIEVE || this == SUPPLY;
    }

    /**
     * Whether a job of this type brings items <b>into</b> the warehouse: a {@link #STORE} from an input station and a
     * {@link #COLLECT} out of a machine (M18, issue #13). Such an arrival, once it really landed in storage, is what may
     * complete an open production order ({@code WarehouseControllerBlockEntity#onResultStored}, ADR-027).
     * <p>
     * A positive test rather than "not a retrieve": the store-vs-retrieve question was asked as {@code type != STORE} in
     * two places before M18, which the new type would have answered wrongly in both.
     */
    public boolean bringsItemsIn() {
        return this == STORE || this == COLLECT;
    }

    /**
     * Whether a crane with this job <b>waits</b> in front of a delivery target that accepts nothing right now, instead of
     * dropping what it can and rerouting the rest ({@code CraneExecution}, {@code ReservationLedger}).
     * <p>
     * True exactly for the two types that deliver on somebody's behalf, {@link #RETRIEVE} and {@link #SUPPLY}: waiting is
     * right when somebody is waiting for the items. A {@link #STORE} into an accepting port must not park the crane in
     * front of a full overflow (M17), and a {@link #COLLECT} never has a delivery target at all.
     */
    public boolean waitsAtAFullTarget() {
        return this == RETRIEVE || this == SUPPLY;
    }

    /**
     * Whether a job of this type may drop at a location of {@code kind}: its planned target, its reroute fallback, and
     * for a {@link #STORE} also an {@link LocationKind#OUTPUT}, i.e. an accepting warehouse port (M17, issue #12).
     */
    public boolean allowsTarget(LocationKind kind) {
        Objects.requireNonNull(kind, "kind");
        return allowedTargets.contains(kind);
    }

    /** The type with the given save name, or empty for {@code null} or unknown names. */
    public static Optional<JobType> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (JobType type : values()) {
            if (type.name().equals(name))
                return Optional.of(type);
        }
        return Optional.empty();
    }
}
