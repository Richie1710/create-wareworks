package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * A scenario's check on <b>every</b> server tick (dev tooling, ADR-014), so nothing can slip between two polls of the
 * harness: a {@link ServerTickEvent.Post} listener that survives a save, quit and rejoin (it asks the server of each
 * tick, never keeps one). A violation cannot throw there (it would crash the integrated server); it is kept and thrown
 * by the next {@link #check}, which every server poll of the scenario calls. {@link RoboBeeLedger} has the same rule for
 * the bee runs.
 */
final class ServerTickWatch {
    private final String prefix;
    private final Consumer<MinecraftServer> check;
    private final Consumer<ServerTickEvent.Post> listener = this::onServerTick;
    @Nullable
    private volatile String failure;
    private volatile boolean paused;
    private long ticks;

    private ServerTickWatch(String prefix, Consumer<MinecraftServer> check) {
        this.prefix = prefix;
        this.check = check;
    }

    static ServerTickWatch start(String prefix, Consumer<MinecraftServer> check) {
        ServerTickWatch watch = new ServerTickWatch(prefix, check);
        NeoForge.EVENT_BUS.addListener(watch.listener);
        return watch;
    }

    void stop() {
        NeoForge.EVENT_BUS.unregister(listener);
    }

    /** While paused (a world is being left or joined) the check does not run. */
    void pause(boolean paused) {
        this.paused = paused;
    }

    long ticks() {
        return ticks;
    }

    /** Throws the first violation any tick found. */
    void check() {
        String found = failure;
        if (found != null)
            throw new VisualTestException(found);
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (failure != null || paused)
            return;
        try {
            ticks++;
            check.accept(event.getServer());
        } catch (RuntimeException error) {
            failure = error instanceof VisualTestException ? error.getMessage() : String.valueOf(error);
            LOGGER.error(PREFIX + "{}: the per-tick check failed: {}", prefix, failure, error);
        }
    }
}
