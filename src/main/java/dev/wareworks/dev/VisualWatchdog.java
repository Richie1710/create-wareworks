package dev.wareworks.dev;

import static dev.wareworks.dev.VisualTestHarness.LOGGER;
import static dev.wareworks.dev.VisualTestHarness.PREFIX;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * Daemon thread that bounds a visual test run.
 * <p>
 * While running: when the deadline passes, the timeout callback gets the current phase (the harness then fails the run on
 * the client thread). After the run ended (passed, failed or timed out) the game gets {@link #SHUTDOWN_GRACE_MILLIS} to exit;
 * otherwise the JVM is halted, with exit code 0 after a pass and {@link #HALT_EXIT_CODE_FAILED} otherwise.
 */
final class VisualWatchdog {
    private static final long CHECK_INTERVAL_MILLIS = 1_000L;
    private static final long SHUTDOWN_GRACE_MILLIS = 60_000L;
    private static final int HALT_EXIT_CODE_PASSED = 0;
    private static final int HALT_EXIT_CODE_FAILED = 3;

    private volatile long deadlineMillis = Long.MAX_VALUE;
    private volatile String phase = "starting";
    private volatile boolean stopping;
    private volatile boolean passed;
    @Nullable
    private volatile Consumer<String> onTimeout;

    void start(long timeoutMillis, Consumer<String> timeoutCallback) {
        onTimeout = timeoutCallback;
        rearm(timeoutMillis, "startup");
        Thread thread = new Thread(this::run, "Wareworks visual test watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /** Sets a new deadline {@code timeoutMillis} from now. */
    void rearm(long timeoutMillis, String newPhase) {
        phase = newPhase;
        deadlineMillis = System.currentTimeMillis() + timeoutMillis;
        LOGGER.info(PREFIX + "watchdog: {} s for {}", timeoutMillis / 1000L, newPhase);
    }

    /** Names what the run is doing (for the timeout message). */
    void phase(String newPhase) {
        phase = newPhase;
    }

    /** The run ended; the game must exit within the grace period. */
    void stopping(boolean runPassed) {
        passed = runPassed;
        stopping = true;
        deadlineMillis = System.currentTimeMillis() + SHUTDOWN_GRACE_MILLIS;
    }

    private void run() {
        while (true) {
            try {
                Thread.sleep(CHECK_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (System.currentTimeMillis() < deadlineMillis)
                continue;
            if (stopping) {
                LOGGER.error(PREFIX + "watchdog: the game did not exit within {} s after the run ended; halting the JVM",
                        SHUTDOWN_GRACE_MILLIS / 1000L);
                Runtime.getRuntime().halt(passed ? HALT_EXIT_CODE_PASSED : HALT_EXIT_CODE_FAILED);
                return;
            }
            String timedOutPhase = phase;
            LOGGER.error(PREFIX + "watchdog: timeout while {}", timedOutPhase);
            stopping(false);
            Consumer<String> callback = onTimeout;
            try {
                if (callback != null)
                    callback.accept(timedOutPhase);
            } catch (RuntimeException error) {
                LOGGER.error(PREFIX + "watchdog: could not hand the timeout to the client thread", error);
            }
        }
    }
}
