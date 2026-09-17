package dev.wareworks.dev;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * State of one visual test run, shared by the steps: scenario, scene origin, shot numbering and index, watchdog.
 * <p>
 * Used on the client thread, except {@link #setOrigin} / {@link #origin()} (volatile, set by a server step) and
 * {@link #serverPlayer} (server thread).
 */
public final class VisualContext {
    /** File name of the shot index inside the screenshot directory. */
    public static final String INDEX_FILE = "index.txt";
    /** Glob of the screenshots this harness writes (older ones are deleted at the start of a run). */
    private static final String SHOT_GLOB = "visual-*.png";

    private final String scenarioName;
    @Nullable
    private final VisualScenario scenario;
    private final VisualWatchdog watchdog;
    private final VisualShotIndex index = new VisualShotIndex();
    @Nullable
    private volatile BlockPos origin;
    private int shotCount;
    /** Sound events the client started to play (muted or not), by id. */
    private final Map<String, Integer> soundCounts = new ConcurrentHashMap<>();

    VisualContext(String scenarioName, @Nullable VisualScenario scenario, VisualWatchdog watchdog) {
        this.scenarioName = scenarioName;
        this.scenario = scenario;
        this.watchdog = watchdog;
    }

    public String scenarioName() {
        return scenarioName;
    }

    @Nullable
    VisualScenario scenario() {
        return scenario;
    }

    VisualWatchdog watchdog() {
        return watchdog;
    }

    VisualShotIndex index() {
        return index;
    }

    public Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    /** The integrated server; fails the run when there is none (not in a world yet). */
    public IntegratedServer server() {
        IntegratedServer server = minecraft().getSingleplayerServer();
        if (server == null)
            throw new VisualTestException("no integrated server is running");
        return server;
    }

    /** Server thread: the (only) player of the integrated server. */
    public ServerPlayer serverPlayer(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty())
            throw new VisualTestException("no player on the integrated server");
        return players.getFirst();
    }

    /** Sets the scene origin that camera views are relative to (any thread; normally a server step of the setup). */
    public void setOrigin(BlockPos origin) {
        this.origin = origin.immutable();
    }

    /** The scene origin; fails the run when the scenario has not set it yet. */
    public BlockPos origin() {
        BlockPos value = origin;
        if (value == null)
            throw new VisualTestException("the scenario has not set its origin");
        return value;
    }

    /** {@code <gameDir>/screenshots}, where vanilla's {@link Screenshot} writes. */
    public Path screenshotDirectory() {
        return minecraft().gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR);
    }

    /** A sound event with id {@code sound} started to play on the client (from {@code PlaySoundEvent}). */
    void recordSound(ResourceLocation sound) {
        soundCounts.merge(sound.toString(), 1, Integer::sum);
    }

    /**
     * How often the client started to play the sound event {@code soundId} (e.g. {@code "create:depot_plop"}) since the run
     * began. Counted before the volume check, so muted sounds count too; server-played sounds only reach the client when
     * the camera is in range (16 blocks for volumes up to 1).
     */
    public int soundCount(String soundId) {
        return soundCounts.getOrDefault(soundId, 0);
    }

    /** Every sound event heard so far with its count, sorted by id. */
    public Map<String, Integer> soundCounts() {
        return new TreeMap<>(soundCounts);
    }

    int nextShotNumber() {
        return ++shotCount;
    }

    /** Status for a shot log line: the scenario's status, the Flywheel backend and whether ticking is frozen. */
    String status() {
        String scenarioStatus;
        try {
            scenarioStatus = scenario == null ? "" : scenario.status(this);
        } catch (RuntimeException error) {
            scenarioStatus = "status-error=" + error;
        }
        ClientLevel level = minecraft().level;
        boolean frozen = level != null && level.tickRateManager().isFrozen();
        return scenarioStatus + " backend=" + flywheelBackend() + " frozen=" + frozen;
    }

    /** The id of Flywheel's current backend ({@code flywheel:off} when disabled), or "unknown". */
    static String flywheelBackend() {
        try {
            return Backend.REGISTRY.getIdOrThrow(BackendManager.currentBackend()).toString();
        } catch (RuntimeException | LinkageError error) {
            return "unknown";
        }
    }

    /** Deletes the screenshots and the index of an earlier run. */
    void cleanScreenshots() throws IOException {
        Path directory = screenshotDirectory();
        if (!Files.isDirectory(directory))
            return;
        try (DirectoryStream<Path> old = Files.newDirectoryStream(directory, SHOT_GLOB)) {
            for (Path file : old)
                Files.deleteIfExists(file);
        }
        Files.deleteIfExists(directory.resolve(INDEX_FILE));
    }

    /** Writes the shot index with {@code result}; logs instead of throwing. */
    void writeIndex(String result) {
        Path file = screenshotDirectory().resolve(INDEX_FILE);
        try {
            index.write(file, scenarioName, result, soundCounts());
        } catch (IOException | RuntimeException error) {
            VisualTestHarness.LOGGER.error(VisualTestHarness.PREFIX + "could not write {}", file, error);
        }
    }
}
