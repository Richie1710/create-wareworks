package dev.wareworks.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Callback;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.CrashReport;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
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
 * <p>
 * The test window runs while the owner works on another monitor, so on its first client ticks the harness turns the
 * sound off ({@link #muteOnce}), stops the window from grabbing the mouse ({@link #keepMouseFree}) and makes it ignore
 * real mouse clicks, mouse movement, scrolling, file drops and key presses ({@link #lockInput}). Scenario input never
 * comes from the devices: world clicks and held keys go through {@code KeyMapping}, screen input through
 * {@link ScreenInput}.
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
    /** Whether {@link #muteOnce} has turned the master volume down yet. */
    private boolean muted;
    /** Whether {@link #keepMouseFree} has replaced the window's focus callback yet. */
    private boolean mouseFreed;
    /** Whether {@link #lockInput} has replaced the window's mouse and keyboard callbacks yet. */
    private boolean inputLocked;
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
        if (harness == null)
            return;
        harness.muteOnce();
        harness.keepMouseFree();
        harness.lockInput();
        harness.tick();
    }

    /**
     * Keeps the test window from taking the mouse. Minecraft grabs the cursor whenever no screen is open and its window
     * counts as active ({@code MouseHandler#grabMouse}), so every world join and every closed screen caught the owner's
     * mouse. Once, the window's focus callback is replaced with one that leaves Minecraft's flag alone, the window no
     * longer minimises when a fullscreen run loses focus, and pausing on lost focus is off; on every tick the flag is
     * set to inactive and a grab that slipped through (a click into the window before this ran) is released. The run
     * goes on while the owner works in another window. Scenario input never needs the grab: world clicks go through
     * {@code KeyMapping}, screen input through {@link ScreenInput}, both without the window's focus.
     */
    private void keepMouseFree() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null || minecraft.getWindow() == null)
            return;
        if (!mouseFreed) {
            long handle = minecraft.getWindow().getWindow();
            Callback previous = GLFW.glfwSetWindowFocusCallback(handle, (window, focused) -> {
            });
            if (previous != null)
                previous.free();
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
            minecraft.options.pauseOnLostFocus = false;
            mouseFreed = true;
            LOGGER.info(PREFIX + "mouse: the test window no longer grabs the cursor");
        }
        minecraft.setWindowActive(false);
        if (minecraft.mouseHandler.isMouseGrabbed())
            minecraft.mouseHandler.releaseMouse();
    }

    /**
     * Makes the test window deaf to the real mouse and keyboard (owner's request 2026-11-08: a real left click into a
     * creative test window broke a block and voided a run, twice). Once, the window's input callbacks that
     * {@code MouseHandler#setup} and {@code KeyboardHandler#setup} install through {@code InputConstants
     * #setupMouseCallbacks}/{@code #setupKeyboardCallbacks} - cursor position, mouse button, scroll, file drop, key and
     * char-with-modifiers - and the cursor enter callback of {@code Window} are replaced with no-ops, and the old ones are
     * freed like the focus callback in {@link #keepMouseFree}. Every mapping is released once, because a key held while
     * the lock went in would never see its release. Window position, size, framebuffer, focus (already replaced),
     * close and error handling stay untouched, so the window can still be moved, resized and closed.
     * <p>
     * Nothing a run sends goes through these callbacks: world clicks and held keys are {@code KeyMapping#click} and
     * {@code KeyMapping#setDown}, screen input is {@link ScreenInput} (the handlers' {@code onPress}, {@code onScroll}
     * and {@code keyPress} called directly, the cursor written into the mouse handler's fields), typed text is the
     * screen's own {@code charTyped}. Without the key callback no real key release can reset a mapping a scenario holds,
     * and without the cursor callback no real mouse moves the cursor a hover step has placed. Minecraft installs these
     * callbacks only once, in its constructor ({@code Minecraft} calls both {@code setup}s; a fullscreen switch or
     * {@code Window#setWindowed} changes the monitor of the same GLFW window), so the lock holds for the whole run. What
     * GLFW reports when polled stays physical: {@code Screen#hasShiftDown} and friends still read the real keyboard,
     * which {@link ScreenInput#requireNoModifiers} guards, and {@code KeyMapping#setAll} would read it too, but only
     * {@code MouseHandler#grabMouse} calls it, which never runs on the inactive window ({@link #keepMouseFree}).
     * Two more polls stay physical while the window has the OS focus: Ponder's hold-W on a tooltip
     * ({@code PonderKeybinds} through {@code InputConstants#isKeyDown}) and Create's pressed look of an
     * {@code IconButton} ({@code AllKeys#isMouseButtonDown}); neither is touched by a scenario.
     */
    private void lockInput() {
        if (inputLocked)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow() == null)
            return;
        long handle = minecraft.getWindow().getWindow();
        freePrevious(GLFW.glfwSetCursorPosCallback(handle, (window, x, y) -> {
        }));
        freePrevious(GLFW.glfwSetMouseButtonCallback(handle, (window, button, action, modifiers) -> {
        }));
        freePrevious(GLFW.glfwSetScrollCallback(handle, (window, x, y) -> {
        }));
        freePrevious(GLFW.glfwSetDropCallback(handle, (window, count, names) -> {
        }));
        freePrevious(GLFW.glfwSetKeyCallback(handle, (window, key, scanCode, action, modifiers) -> {
        }));
        freePrevious(GLFW.glfwSetCharModsCallback(handle, (window, codePoint, modifiers) -> {
        }));
        freePrevious(GLFW.glfwSetCursorEnterCallback(handle, (window, entered) -> {
        }));
        KeyMapping.releaseAll();
        inputLocked = true;
        LOGGER.info(PREFIX + "input: the test window ignores real mouse and keyboard input");
    }

    private static void freePrevious(@Nullable Callback previous) {
        if (previous != null)
            previous.free();
    }

    /**
     * Turns the master volume to zero on the first tick that has options, so a test window plays nothing through the
     * speakers. Only in memory: nothing here saves the options. The sound counts are unaffected, because
     * {@link PlaySoundEvent} fires before the sound engine looks at the volume.
     */
    private void muteOnce() {
        if (muted)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null)
            return;
        minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        muted = true;
        LOGGER.info(PREFIX + "sound: master volume set to 0 for the run");
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
