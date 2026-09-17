package dev.wareworks.content.crane;

import java.util.Locale;

/**
 * Why a stacker crane is paused ({@code docs/stacker-crane.md} §4: "paused" is a flag, not a phase). While paused,
 * motion, transfer and retry timers stop; nothing is lost and the crane continues when the reason is gone.
 * {@link #name()} is the stable sync name.
 */
public enum CranePauseReason {
    /** Not paused. */
    NONE,
    /** The dock receives no rotation. */
    NO_ROTATION,
    /** The kinetic network is overstressed ({@code getSpeed()} is 0). */
    OVERSTRESSED,
    /** The aisle position under the crane or the location of the current stop is in a chunk that is not loaded. */
    CHUNK_NOT_LOADED,
    /**
     * A crane speed factor is 0 in the server config ({@code crane.*}, {@code docs/warehouse-system.md} §9). That axis
     * would never arrive while the others move, so the crane is held still instead and the reason names the
     * configuration rather than the machine ({@code docs/stacker-crane.md} §4.2).
     */
    SPEED_FACTOR_ZERO;

    private static final String LANG_PREFIX = "gui.goggles.crane_pause_reason.";

    /** Relative lang key of the reason text, e.g. {@code gui.goggles.crane_pause_reason.no_rotation}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /** The reason with the given sync name, or {@link #NONE} for {@code null} or unknown names. */
    public static CranePauseReason byName(String name) {
        for (CranePauseReason reason : values()) {
            if (reason.name().equals(name))
                return reason;
        }
        return NONE;
    }
}
