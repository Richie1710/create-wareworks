package dev.wareworks.client.gui;

import java.util.Optional;

import dev.wareworks.network.StockKeeperScreenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Where the stock keeper payload lands on the client ({@code docs/warehouse-system.md} §3.6).
 * <p>
 * The server sends it to exactly one player and only while that player has a stock keeper screen open, but a payload
 * can still arrive one tick after the screen was closed or replaced. Every update is therefore matched against the
 * screen that is open <b>right now</b> and its container id; anything else is dropped without a trace.
 * <p>
 * Client only: named from {@code network.WareworksNetwork} inside a handler body that never runs on a dedicated
 * server.
 */
public final class StockKeeperScreenUpdates {
    private StockKeeperScreenUpdates() {
    }

    /** The rules of the open screen's keeper and what the warehouse holds of them. */
    public static void onState(StockKeeperScreenPayload payload) {
        screen(payload.containerId()).ifPresent(screen -> screen.onState(payload));
    }

    private static Optional<WarehouseStockKeeperScreen> screen(int containerId) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof WarehouseStockKeeperScreen keeper
                && keeper.getMenu().containerId == containerId)
            return Optional.of(keeper);
        return Optional.empty();
    }
}
