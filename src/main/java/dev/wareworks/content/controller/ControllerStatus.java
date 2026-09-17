package dev.wareworks.content.controller;

import java.util.Optional;

/**
 * State of a warehouse controller's link to its aisle ({@code docs/warehouse-system.md} §3.3). {@link #name()} is the
 * stable sync name.
 */
public enum ControllerStatus {
    /** No stacker crane dock directly in front of the controller. */
    NO_DOCK,
    /**
     * A stacker crane dock stands directly in front of the controller, but it faces another direction, so it belongs to
     * no controller on this side. Told apart from {@link #NO_DOCK} because the goggles otherwise say "no stacker crane
     * in front" while the player is looking straight at one, which is the most likely build mistake of an aisle
     * ({@code docs/warehouse-system.md} §3.3.1).
     */
    DOCK_MISALIGNED,
    /** The dock is linked, but no warehouse rails lie in front of it (only rack position 0 exists). */
    NO_RAILS,
    /** Dock and rails found: the aisle is defined. */
    READY;

    /** The status with the given sync name, or empty for {@code null} or unknown names. */
    public static Optional<ControllerStatus> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (ControllerStatus status : values()) {
            if (status.name().equals(name))
                return Optional.of(status);
        }
        return Optional.empty();
    }
}
