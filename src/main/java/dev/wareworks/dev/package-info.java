/**
 * Dev tooling: automated visual smoke test that checks rendering without anyone watching the game window.
 * <p>
 * Inactive unless the JVM system property {@value dev.wareworks.dev.VisualTestHarness#PROPERTY} names a scenario
 * (Gradle run {@code runVisualTest}). {@code WareworksClient} is the only class referencing this package, and only when the
 * property is set, so neither the dedicated server nor a normal client loads any class of it.
 * <p>
 * Flow ({@link dev.wareworks.dev.VisualTestHarness}): title screen → fresh superflat creative world → scenario build on the
 * integrated server → camera tour with screenshots (Flywheel backend on, then off) → {@code screenshots/index.txt} → game
 * stops. A failure or the watchdog stops the game with a crash report (non-zero exit code).
 */
package dev.wareworks.dev;
