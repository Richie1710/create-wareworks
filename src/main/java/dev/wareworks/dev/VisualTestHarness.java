package dev.wareworks.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Entry point and step runner of the automated visual smoke test (dev tooling, client only).
 * <p>
 * {@link #install} is called from {@code WareworksClient} only when the system property {@value #PROPERTY} is set; its value
 * names the scenario ({@link VisualScenarios}). The run is a flat list of {@link VisualStep}s: world preparation
 * ({@link VisualWorld}), the scenario's setup, then one camera tour per {@link VisualPass} (Flywheel backend on, then off).
 * One step is current at a time; it is ticked on every client tick ({@link ClientTickEvent.Post}) and sees every rendered
 * frame ({@link RenderFrameEvent.Post}, where screenshots are grabbed).
 * <p>
 * On success the shot index is written and the game stops cleanly ({@link Minecraft#stop()}, exit code 0). On any exception,
 * step timeout or watchdog timeout the index is written with the reason and the game stops through a delayed crash report,
 * so the Gradle task fails. Every step and shot is logged with the prefix {@value #PREFIX}.
 */
public final class VisualTestHarness {
    /** System property naming the scenario; the harness is inactive when it is unset. */
    public static final String PROPERTY = "wareworks.visualTest";
    /** Prefix of every log line of the harness. */
    public static final String PREFIX = "[WareworksVisual] ";
    static final Logger LOGGER = LogUtils.getLogger();

    /** Game start until the title screen (resource loading, shader compilation). */
    private static final long STARTUP_TIMEOUT_MILLIS = 5L * 60L * 1000L;
    /** Title screen until the last shot. */
    static final long RUN_TIMEOUT_MILLIS = 4L * 60L * 1000L;

    @Nullable
    private static volatile VisualTestHarness active;

    private final VisualContext context;
    private final List<VisualStep> steps;
    private int nextStep;
    @Nullable
    private VisualStep current;
    private int currentTicks;
    private boolean finished;
    /** Whether a step is being ticked right now; nested client tick loops must not advance the run. */
    private boolean ticking;
    /** Whether a frame callback is running right now; the client renders nested inside steps that pump its loop. */
    private boolean framing;

    private VisualTestHarness(VisualContext context, List<VisualStep> steps) {
        this.context = context;
        this.steps = List.copyOf(steps);
    }

    /**
     * Installs the harness for {@code scenarioName}: registers the client tick and frame listeners and starts the watchdog.
     * An unknown scenario fails the run on the first client tick.
     */
    public static void install(String scenarioName) {
        if (active != null) {
            LOGGER.warn(PREFIX + "already installed; ignoring scenario '{}'", scenarioName);
            return;
        }
        Optional<VisualScenario> scenario = VisualScenarios.byName(scenarioName);
        VisualWatchdog watchdog = new VisualWatchdog();
        VisualContext context = new VisualContext(scenarioName, scenario.orElse(null), watchdog);
        VisualTestHarness harness = new VisualTestHarness(context, scenario.map(s -> plan(s)).orElseGet(List::of));
        active = harness;
        NeoForge.EVENT_BUS.addListener(VisualTestHarness::onClientTick);
        NeoForge.EVENT_BUS.addListener(VisualTestHarness::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(VisualTestHarness::onPlaySound);
        watchdog.start(STARTUP_TIMEOUT_MILLIS, harness::onWatchdogTimeout);
        LOGGER.info(PREFIX + "installed: scenario '{}', {} steps", scenarioName, harness.steps.size());
    }

    /** World preparation, the scenario's setup, one tour per pass, then the scenario's closing steps. */
    private static List<VisualStep> plan(VisualScenario scenario) {
        VisualScript script = new VisualScript();
        scenario.prepare(script);
        scenario.setup(script);
        for (VisualPass pass : VisualPass.values()) {
            script.beginPass(pass);
            scenario.pass(pass, script);
        }
        scenario.finish(script);
        return new ArrayList<>(script.steps());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        VisualTestHarness harness = active;
        if (harness != null)
            harness.tick();
    }

    private static void onRenderFrame(RenderFrameEvent.Post event) {
        VisualTestHarness harness = active;
        if (harness != null)
            harness.frame();
    }

    /** Counts every sound the client starts to play, so scenarios can check that machines were heard. */
    private static void onPlaySound(PlaySoundEvent event) {
        VisualTestHarness harness = active;
        if (harness != null)
            harness.context.recordSound(event.getOriginalSound().getLocation());
    }

    private void tick() {
        // Re-entrancy guard: loading a world runs the client's own loop from inside a step, so this listener can fire
        // again while that step is still running and the harness would start the next steps too early. Quitting to the
        // title screen pumps the loop with `runTick(false)` (Minecraft#disconnect), which renders but never calls
        // Minecraft#tick, so no client tick arrives from there - see the matching guard in frame().
        if (finished || ticking)
            return;
        ticking = true;
        try {
            tickStep();
        } finally {
            ticking = false;
        }
    }

    private void tickStep() {
        if (context.scenario() == null) {
            fail("scenario lookup", new VisualTestException("unknown scenario '" + context.scenarioName()
                    + "'; known scenarios: " + VisualScenarios.names()));
            return;
        }
        try {
            if (current == null && !startNextStep()) {
                succeed();
                return;
            }
            VisualStep step = current;
            currentTicks++;
            if (step.tick(context)) {
                LOGGER.info(PREFIX + "step {}/{} done after {} ticks: {}", nextStep, steps.size(), currentTicks,
                        step.describe());
                current = null;
            } else if (currentTicks > step.timeoutTicks()) {
                throw new VisualTestException("step timed out after " + step.timeoutTicks() + " ticks: " + step.describe());
            }
        } catch (Throwable error) {
            fail(describeCurrent(), error);
        }
    }

    private void frame() {
        VisualStep step = current;
        // The re-entrant callback is this one: `Minecraft#disconnect` pumps `runTick(false)`, which skips the client
        // tick but still renders and posts RenderFrameEvent.Post while the level is being torn down. Without the
        // guards a shot step around a world reload would grab a screenshot with `minecraft.level` already null.
        if (finished || ticking || framing || step == null)
            return;
        framing = true;
        try {
            step.afterFrame(context);
        } catch (Throwable error) {
            fail(describeCurrent(), error);
        } finally {
            framing = false;
        }
    }

    private boolean startNextStep() {
        if (nextStep >= steps.size())
            return false;
        VisualStep step = steps.get(nextStep++);
        current = step;
        currentTicks = 0;
        context.watchdog().phase(step.describe());
        LOGGER.info(PREFIX + "step {}/{}: {}", nextStep, steps.size(), step.describe());
        step.start(context);
        return true;
    }

    private String describeCurrent() {
        VisualStep step = current;
        return step == null ? "between steps" : "step " + nextStep + "/" + steps.size() + " (" + step.describe() + ")";
    }

    private void succeed() {
        finished = true;
        context.writeIndex("PASSED");
        LOGGER.info(PREFIX + "PASSED: {} shots in {}", context.index().size(), context.screenshotDirectory());
        LOGGER.info(PREFIX + "sounds heard: {}", context.soundCounts());
        context.watchdog().stopping(true);
        Minecraft.getInstance().stop();
    }

    /** Fails the run (client thread): logs, writes the index with the reason and stops the game with a crash report. */
    void fail(String where, @Nullable Throwable error) {
        if (finished)
            return;
        finished = true;
        String reason = where + (error == null ? "" : ": " + error);
        LOGGER.error(PREFIX + "FAILED during " + where, error);
        context.writeIndex("FAILED: " + reason);
        context.watchdog().stopping(false);
        Throwable cause = error != null ? error : new VisualTestException(where);
        Minecraft.getInstance().delayCrash(CrashReport.forThrowable(cause, "Wareworks visual smoke test failed: " + where));
    }

    /** Watchdog thread: hands the failure to the client thread (the watchdog halts the JVM if that never runs). */
    private void onWatchdogTimeout(String phase) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null)
            minecraft.execute(() -> fail("watchdog timeout while " + phase, null));
    }
}
