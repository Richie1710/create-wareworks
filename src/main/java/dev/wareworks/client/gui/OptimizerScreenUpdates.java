package dev.wareworks.client.gui;

import java.util.Optional;

import dev.wareworks.network.OptimizerScreenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Where the optimizer payload lands on the client (issue #46, ADR-079).
 * <p>
 * The server sends it to exactly one player and only while that player has an optimizer screen open, but a payload can
 * still arrive one tick after the screen was closed or replaced. Every update is therefore matched against the screen
 * that is open <b>right now</b> and its container id; anything else is dropped without a trace.
 * <p>
 * Client only: named from {@code network.WareworksNetwork} inside a handler body that never runs on a dedicated
 * server.
 */
public final class OptimizerScreenUpdates {
    private OptimizerScreenUpdates() {
    }

    /** The rows of the open screen's optimizer and what the warehouse holds of their items. */
    public static void onState(OptimizerScreenPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onState(payload));
    }

    private static Optional<WarehouseOptimizerScreen> screen(int containerId) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof WarehouseOptimizerScreen optimizer && optimizer.getMenu().containerId == containerId)
            return Optional.of(optimizer);
        return Optional.empty();
    }
}
