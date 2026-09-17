package dev.wareworks.client.gui;

import java.util.Optional;

import dev.wareworks.network.ProductionScreenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Where the production station payload lands on the client ({@code docs/warehouse-system.md} §3.5).
 * <p>
 * The server sends it to exactly one player and only while that player has a production screen open, but a payload can
 * still arrive one tick after the screen was closed or replaced. Every update is therefore matched against the screen
 * that is open <b>right now</b> and its container id; anything else is dropped without a trace.
 * <p>
 * Client only: named from {@code network.WareworksNetwork} inside a handler body that never runs on a dedicated
 * server.
 */
public final class ProductionScreenUpdates {
    private ProductionScreenUpdates() {
    }

    /** The patterns and production orders of the open screen's station. */
    public static void onState(ProductionScreenPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onState(payload));
    }

    private static Optional<WarehouseProductionScreen> screen(int containerId) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof WarehouseProductionScreen production
                && production.getMenu().containerId == containerId)
            return Optional.of(production);
        return Optional.empty();
    }
}
