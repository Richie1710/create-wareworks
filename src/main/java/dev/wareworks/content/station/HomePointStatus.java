package dev.wareworks.content.station;

import java.util.Locale;
import java.util.Optional;

/**
 * What a warehouse home point is doing right now, as its lamps and its goggle tooltip show it
 * ({@code docs/stacker-crane.md} §4.7, M21, ADR-034).
 * <p>
 * Derived by the controller that serves the home point and synced to clients in the block entity's packet; never
 * authoritative and never saved. {@link #name()} is only used inside a client packet, so it needs no migration.
 * <p>
 * <b>Nothing here is silent.</b> Every way a home point can fail to be used — a second one in the same warehouse, an
 * aisle the crane cannot drive to, a warehouse that never bends, a server that switched returning home off — is its own
 * value with its own sentence, because a player who placed a block and sees nothing happen has no way to find out why.
 */
public enum HomePointStatus {
    /** Not an aligned member of a loaded warehouse: it says nothing about any crane. */
    NO_WAREHOUSE(false, false),
    /** The crane of this warehouse waits here. */
    SERVING(true, false),
    /**
     * This warehouse has another home point that comes first, and a warehouse has one crane, so it has one home. The
     * first one in {@code RackPosition.ORDER} wins, which is the same one on every tick and after every restart.
     */
    SECOND(false, true),
    /**
     * The crane cannot drive here: the rails between it and this aisle are broken, closed or gone. Reported rather
     * than obeyed — the crane keeps waiting where it is instead of trying to reach a place it cannot.
     */
    UNREACHABLE(false, true),
    /**
     * The warehouse is one straight aisle, so its crane stays exactly where its last job left it. That is what every
     * warehouse did before this version and it is deliberately unchanged; a home point pays off the moment the
     * warehouse bends.
     */
    SINGLE_AISLE(false, false),
    /** The server switched returning home off ({@code crane.returnHomeIdleTicks = 0}). */
    SWITCHED_OFF(false, false);

    private final boolean lit;
    private final boolean refused;

    HomePointStatus(boolean lit, boolean refused) {
        this.lit = lit;
        this.refused = refused;
    }

    /** Whether the green lamp burns: this is the home point the crane really uses. */
    public boolean isLit() {
        return lit;
    }

    /** Whether the red lamp burns: something a player has to fix keeps this home point from being used. */
    public boolean isRefused() {
        return refused;
    }

    /** Relative lang key of the goggle sentence of this status. */
    public String langKey() {
        return "gui.goggles.home_point_" + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The status with the given name, or empty for {@code null} or unknown names (untrusted packet data). */
    public static Optional<HomePointStatus> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (HomePointStatus status : values()) {
            if (status.name().equals(name))
                return Optional.of(status);
        }
        return Optional.empty();
    }
}
