package dev.wareworks.content.computer;

import java.util.Locale;

import dev.wareworks.content.controller.ControllerStatus;
import dev.wareworks.content.controller.RequestRejection;
import dev.wareworks.core.port.DoorState;

/**
 * The words a computer reads back from a warehouse terminal (issue #9, ADR-074, {@code docs/computercraft.md}).
 * <p>
 * Lua replies are machine replies, not game text: they are English, never translated, and every one of them is the
 * lowercase name of the enum constant it stands for, so a script can compare them and the documentation can list them.
 * {@code ComputerRepliesTest} pins the lists against the enums, so a new constant cannot appear without the
 * documentation being updated. This class names no Minecraft type.
 */
public final class ComputerReplies {
    /** {@link #status} of a terminal outside any warehouse, or whose controller is not loaded. */
    public static final String NO_CONTROLLER = "no_controller";

    private ComputerReplies() {
    }

    /** Why a request was refused, e.g. {@code not_in_stock}. */
    public static String rejection(RequestRejection rejection) {
        return word(rejection);
    }

    /** The terminal's warehouse state: {@link #NO_CONTROLLER} without one, else the controller's, e.g. {@code ready}. */
    public static String status(boolean hasController, ControllerStatus status) {
        return hasController ? word(status) : NO_CONTROLLER;
    }

    /** How a package door stands, e.g. {@code box_waiting}. */
    public static String doorState(DoorState state) {
        return word(state);
    }

    private static String word(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }
}
