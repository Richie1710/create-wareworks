package dev.wareworks.dev;

/** A visual test step found the run cannot continue; the harness fails the run with this message. */
public final class VisualTestException extends RuntimeException {
    public VisualTestException(String message) {
        super(message);
    }
}
