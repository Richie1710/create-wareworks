package dev.wareworks.core.warehouse;

import java.util.Objects;
import java.util.Optional;

/**
 * What a membership scan found at one rack position ({@code docs/warehouse-system.md} §4). The content layer produces
 * it from the world; {@link AisleMembership} turns a series of probes into membership changes.
 */
public enum RackProbe {
    /** The position's chunk is not loaded: nothing is known, a persisted record is kept. */
    UNLOADED(null),
    /** No warehouse member at the position. */
    EMPTY(null),
    /** A warehouse member whose facing does not satisfy the rule of its kind (reported in goggles). */
    MISALIGNED(null),
    /** An aligned warehouse interface. */
    STORAGE(LocationKind.STORAGE),
    /** An aligned warehouse input. */
    INPUT(LocationKind.INPUT),
    /** An aligned warehouse output. */
    OUTPUT(LocationKind.OUTPUT),
    /** An aligned warehouse production station. */
    PRODUCTION(LocationKind.PRODUCTION),
    /** An aligned warehouse stock keeper ({@code docs/warehouse-system.md} §3.6, M15). */
    KEEPER(LocationKind.KEEPER);

    private final LocationKind kind;

    RackProbe(LocationKind kind) {
        this.kind = kind;
    }

    /** The member kind of an aligned member, empty otherwise. */
    public Optional<LocationKind> kind() {
        return Optional.ofNullable(kind);
    }

    /** The probe result of an aligned member of {@code kind}. */
    public static RackProbe member(LocationKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case STORAGE -> STORAGE;
            case INPUT -> INPUT;
            case OUTPUT -> OUTPUT;
            case PRODUCTION -> PRODUCTION;
            case KEEPER -> KEEPER;
        };
    }
}
