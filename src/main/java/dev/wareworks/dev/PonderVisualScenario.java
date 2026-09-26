package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.tterrag.registrate.util.entry.ItemProviderEntry;

import dev.wareworks.registry.WareworksBlocks;
import net.createmod.ponder.api.registration.SceneRegistryAccess;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Scenario "ponder": opens the real Ponder UI for every Wareworks item and screenshots each scene at three moments
 * (M5, ADR-016).
 * <p>
 * This is the only automated way to see that the scenes actually work. Compiling a scene runs its storyboard against a
 * real {@code PonderLevel}, places the schematic, runs every instruction and renders the blocks and block entities with
 * their block entity renderers, so this run catches a missing schematic, an out-of-bounds {@code setBlock}, a crashing
 * instruction, a raw lang key and a crane that does not move. A storyboard that throws propagates out of
 * {@code PonderUI.of}, which fails the harness with a crash report and a non-zero Gradle exit code.
 * <p>
 * Per subject the scenario asserts the <b>number of registered scenes</b> before opening the UI, so a lost or duplicated
 * {@code addStoryBoard} fails the run rather than silently changing the screenshots.
 * <p>
 * Scenes are advanced with {@code PonderUI#mouseScrolled} (the public route to its protected {@code scroll}), which
 * returns false at the last scene, and moments are reached with {@code PonderUI#seekToTime}, which fast-forwards the
 * scene deterministically (the crane's block entity ticks along with it, so its pose at a moment is reproducible).
 * <p>
 * Ponder never uses Flywheel ({@code VisualizationManager.supportsVisualization} is false for a {@code PonderLevel}),
 * so the second pass only re-shoots one scene as a regression check instead of the whole tour.
 */
public final class PonderVisualScenario implements VisualScenario {
    public static final String NAME = "ponder";

    private static final int OPEN_TIMEOUT_TICKS = 200;
    /** Ticks after opening or switching a scene before the first shot (widget fade-in). */
    private static final int SETTLE_TICKS = 10;
    /** Ticks after a seek, so the frame after the fast-forward has been rendered. */
    private static final int SEEK_SETTLE_TICKS = 3;

    /**
     * Fractions of a scene's total time that get a screenshot, and their shot labels. Every scene spends its first
     * third introducing blocks and its last two thirds running the crane, so these land on the reveal, on a transfer
     * and on the closing beat rather than all three in the introduction.
     */
    private static final int[] MOMENT_PERCENT = {20, 60, 90};
    private static final String[] MOMENT_LABEL = {"start", "middle", "end"};

    /**
     * One pondered item.
     *
     * @param label          short name used in shot labels
     * @param block          the block whose item opens the scenes
     * @param expectedScenes how many storyboards {@code WareworksPonderScenes} registers for it
     */
    private record Subject(String label, ItemProviderEntry<?, ?> block, int expectedScenes) {
        ResourceLocation id() {
            return BuiltInRegistries.ITEM.getKey(block.asItem());
        }
    }

    private static final List<Subject> SUBJECTS = List.of(
            new Subject("crane", WareworksBlocks.STACKER_CRANE, 1),
            new Subject("rail", WareworksBlocks.WAREHOUSE_RAIL, 1),
            new Subject("controller", WareworksBlocks.WAREHOUSE_CONTROLLER, 2),
            new Subject("interface", WareworksBlocks.WAREHOUSE_INTERFACE, 3),
            new Subject("input", WareworksBlocks.WAREHOUSE_INPUT, 1),
            new Subject("output", WareworksBlocks.WAREHOUSE_OUTPUT, 1),
            new Subject("terminal", WareworksBlocks.WAREHOUSE_TERMINAL, 2),
            new Subject("production", WareworksBlocks.WAREHOUSE_PRODUCTION, 1),
            new Subject("keeper", WareworksBlocks.WAREHOUSE_STOCK_KEEPER, 2));

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Ponder only needs a client level to exist ({@code new PonderLevel(BlockPos.ZERO, minecraft.level)}), so the
     * scenario builds nothing. It only records an origin, because camera views and the scenario contract expect one.
     */
    @Override
    public void setup(VisualScript script) {
        script.server("ponder: remember the scene origin", PonderVisualScenario::rememberOrigin);
    }

    @Override
    public void pass(VisualPass pass, VisualScript script) {
        if (pass == VisualPass.FLYWHEEL) {
            for (Subject subject : SUBJECTS)
                tour(script, subject, MOMENT_PERCENT, MOMENT_LABEL);
            return;
        }
        // Ponder always renders through block entity renderers, never through Flywheel: one shot shows it is identical.
        tour(script, SUBJECTS.getFirst(), new int[] {MOMENT_PERCENT[1]}, new String[] {MOMENT_LABEL[1]});
    }

    @Override
    public String status(VisualContext context) {
        return activeUi(context).map(ui -> {
            PonderScene scene = ui.getActiveScene();
            return String.format(Locale.ROOT, "scene=%s t=%d/%d", scene.getId(), scene.getCurrentTime(),
                    scene.getTotalTime());
        }).orElse("scene=none");
    }

    // --- tour ---------------------------------------------------------------------------------------------------------

    /** Opens the subject's scenes and shoots every scene at every moment, then closes the screen again. */
    private void tour(VisualScript script, Subject subject, int[] percents, String[] labels) {
        script.client("ponder: open the scenes of " + subject.label(), context -> open(context, subject))
                .until("ponder: the Ponder screen of " + subject.label() + " is shown",
                        context -> activeUi(context).isPresent(), OPEN_TIMEOUT_TICKS)
                .waitTicks(SETTLE_TICKS);

        for (int sceneIndex = 0; sceneIndex < subject.expectedScenes(); sceneIndex++) {
            if (sceneIndex > 0) {
                int expected = sceneIndex;
                script.client("ponder: go to scene " + expected + " of " + subject.label(),
                                context -> advance(context, subject, expected))
                        .waitTicks(SETTLE_TICKS);
            }
            for (int moment = 0; moment < percents.length; moment++) {
                int percent = percents[moment];
                script.client("ponder: seek " + subject.label() + " scene " + sceneIndex + " to " + percent + "%",
                                context -> seek(context, percent))
                        .waitTicks(SEEK_SETTLE_TICKS)
                        .shot(subject.label() + "-s" + sceneIndex + "-" + labels[moment]);
            }
        }

        script.client("ponder: close the scenes of " + subject.label(),
                        context -> context.minecraft().setScreen(null))
                .until("ponder: the Ponder screen is closed", context -> context.minecraft().screen == null,
                        OPEN_TIMEOUT_TICKS);
    }

    // --- client actions -----------------------------------------------------------------------------------------------

    /** Checks the registration and opens the Ponder UI, which compiles and starts the subject's scenes. */
    private static void open(VisualContext context, Subject subject) {
        ResourceLocation id = subject.id();
        SceneRegistryAccess scenes = PonderIndex.getSceneAccess();
        if (!scenes.doScenesExistForId(id))
            throw new VisualTestException("no Ponder scenes are registered for " + id);
        long registered = scenes.getRegisteredEntries().stream().filter(entry -> entry.getKey().equals(id)).count();
        if (registered != subject.expectedScenes())
            throw new VisualTestException("expected " + subject.expectedScenes() + " Ponder scenes for " + id
                    + ", but " + registered + " are registered");
        LOGGER.info(PREFIX + "ponder: opening {} ({} scenes)", id, registered);
        // Compiling runs every storyboard against a real PonderLevel; a broken scene throws out of here.
        context.minecraft().setScreen(PonderUI.of(id));
    }

    /** Scrolls to the next scene; {@code mouseScrolled} returns false when there is none. */
    private static void advance(VisualContext context, Subject subject, int expectedIndex) {
        PonderUI ui = requireUi(context);
        if (!ui.mouseScrolled(0.0, 0.0, 0.0, 1.0))
            throw new VisualTestException("Ponder scene " + expectedIndex + " of " + subject.label()
                    + " is missing: the UI would not scroll past scene " + (expectedIndex - 1));
        LOGGER.info(PREFIX + "ponder: {} scene {} is {}", subject.label(), expectedIndex, ui.getActiveScene().getId());
    }

    /** Fast-forwards the active scene to {@code percent} of its total time. */
    private static void seek(VisualContext context, int percent) {
        PonderUI ui = requireUi(context);
        PonderScene scene = ui.getActiveScene();
        int total = scene.getTotalTime();
        if (total <= 0)
            throw new VisualTestException("Ponder scene " + scene.getId() + " has no length; it schedules no idle()");
        ui.seekToTime(Math.max(1, total * percent / 100));
        LOGGER.info(PREFIX + "ponder: {} at {}/{} ticks", scene.getId(), scene.getCurrentTime(), total);
    }

    private static Optional<PonderUI> activeUi(VisualContext context) {
        return context.minecraft().screen instanceof PonderUI ui ? Optional.of(ui) : Optional.empty();
    }

    private static PonderUI requireUi(VisualContext context) {
        return activeUi(context).orElseThrow(() -> new VisualTestException("no Ponder screen is open"));
    }

    // --- server actions -----------------------------------------------------------------------------------------------

    private static void rememberOrigin(MinecraftServer server, VisualContext context) {
        ServerLevel level = server.overworld();
        context.setOrigin(new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0));
    }
}
