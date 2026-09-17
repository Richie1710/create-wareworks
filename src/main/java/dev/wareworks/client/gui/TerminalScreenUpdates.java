package dev.wareworks.client.gui;

import java.util.Optional;

import dev.wareworks.network.TerminalOrdersPayload;
import dev.wareworks.network.TerminalResultPayload;
import dev.wareworks.network.TerminalStatusPayload;
import dev.wareworks.network.TerminalStockPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Where the terminal payloads land on the client ({@code docs/architecture.md} ADR-019).
 * <p>
 * The server sends them to exactly one player, and only while that player has a terminal menu open, but a payload can
 * still arrive one tick after the screen was closed or replaced. Every update is therefore matched against the screen
 * that is open <b>right now</b> and its container id; anything else is dropped without a trace.
 * <p>
 * Client only: this class is named from {@code network.WareworksNetwork} inside handler bodies that never run on a
 * dedicated server.
 */
public final class TerminalScreenUpdates {
    private TerminalScreenUpdates() {
    }

    /** New, changed or removed item types for the open screen. */
    public static void onStock(TerminalStockPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onStock(payload));
    }

    /** The aisle and crane state for the open screen. */
    public static void onStatus(TerminalStatusPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onStatus(payload));
    }

    /** The answer to a request the open screen sent. */
    public static void onResult(TerminalResultPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onResult(payload));
    }

    /** The production orders of the open screen's aisle (M11, ADR-024). */
    public static void onOrders(TerminalOrdersPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onOrders(payload));
    }

    /** The open terminal screen of the menu {@code containerId}, if that is what the player is looking at. */
    private static Optional<WarehouseTerminalScreen> screen(int containerId) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof WarehouseTerminalScreen terminal && terminal.getMenu().containerId == containerId)
            return Optional.of(terminal);
        return Optional.empty();
    }
}
