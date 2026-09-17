package dev.wareworks.dev;

/**
 * One step of a visual test run (client thread). Steps are built through {@link VisualScript}.
 * <p>
 * Lifecycle: {@link #start} once, then {@link #tick} on every client tick (the first time in the same tick) until it returns
 * {@code true}; {@link #afterFrame} after every rendered frame while the step is current. Throwing from any method, or
 * needing more than {@link #timeoutTicks()} ticks, fails the run.
 */
public interface VisualStep {
    /** Short description for the log. */
    String describe();

    default void start(VisualContext context) {
    }

    /** @return whether the step is done */
    boolean tick(VisualContext context);

    default void afterFrame(VisualContext context) {
    }

    /** Client ticks the step may take. */
    int timeoutTicks();
}
