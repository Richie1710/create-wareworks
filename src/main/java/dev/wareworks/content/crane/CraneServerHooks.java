package dev.wareworks.content.crane;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Server lifecycle hooks of the crane subsystem ({@code docs/stacker-crane.md} §4.2).
 * <p>
 * The crane's diagnostics are rate-limited per <b>server run</b>, but their flags are class statics, and a
 * single-player client's JVM outlives every integrated server it starts. Without a reset at server start, a player who
 * fixes {@code wareworks-server.toml} and rejoins would never see the warning again in that game session. Registered
 * once from {@code Wareworks}; safe on both dists.
 */
public final class CraneServerHooks {
    private CraneServerHooks() {
    }

    /** Registers the hooks on the NeoForge game event bus. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(CraneServerHooks::onServerStarting);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        CraneExecution.onServerStarting();
    }
}
