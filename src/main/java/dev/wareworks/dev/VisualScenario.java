package dev.wareworks.dev;

/**
 * A visual test scenario: builds a scene once and describes the camera tour of each render pass.
 * <p>
 * To add one: implement this interface in {@code dev.wareworks.dev}, register it in {@link VisualScenarios}, and run
 * {@code ./gradlew runVisualTest -Pwareworks.visualTest=<name>}.
 */
public interface VisualScenario {
    /** Scenario name, the value of the system property and part of every screenshot file name. */
    String name();

    /**
     * How the harness prepares the world for this scenario. The default is a throw-away world with a spectator camera
     * ({@link VisualWorldProfile#camera()}); a scenario whose world is meant to be kept and opened by a player
     * afterwards overrides this with {@link VisualWorldProfile#playable}.
     */
    default VisualWorldProfile worldProfile() {
        return VisualWorldProfile.camera();
    }

    /**
     * Steps that build the scene once, after the world is ready. One of them must call
     * {@link VisualContext#setOrigin} (camera views are relative to it). Prefer real block placement on the server thread
     * ({@link VisualScript#server}) over commands, and wait until the scene is ready ({@link VisualScript#serverUntil}).
     */
    void setup(VisualScript script);

    /**
     * Steps of one render pass: camera moves and shots ({@link VisualScript#shotFrom}). Called once per {@link VisualPass};
     * the harness has switched the Flywheel backend before, and shot labels get the pass prefix.
     */
    void pass(VisualPass pass, VisualScript script);

    /** One line of scenario state for the shot log (client thread), e.g. crane pose and phase. */
    String status(VisualContext context);
}
