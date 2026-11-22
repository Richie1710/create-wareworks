package dev.wareworks.content.packagemode;

import dev.wareworks.network.WareworksNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Package mode's one game-bus listener (issue #40, review fix 2026-11-22): a player who joins is told the switch as the
 * running server holds it ({@code PackageModeSwitchPayload}), which is what that client's screens and goggles read.
 * Sent with the switch off too, so a client never keeps the answer of a server it played on before.
 */
public final class PackageModeHooks {
    private PackageModeHooks() {
    }

    /** Adds the listener to the game event bus; called once from the {@code Wareworks} constructor. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(PackageModeHooks::onLoggedIn);
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            WareworksNetwork.sendPackageModeSwitch(player);
    }
}
