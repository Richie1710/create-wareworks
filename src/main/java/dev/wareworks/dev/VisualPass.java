package dev.wareworks.dev;

/**
 * Render passes of a visual test run, in order. Each pass switches Flywheel's backend with its client command
 * ({@code /flywheel backend ...}, Flywheel 1.0.6 {@code FlwCommands}) and runs the scenario's camera tour again.
 */
public enum VisualPass {
    /** Flywheel's default backend (instancing or indirect, whatever the GPU supports). */
    FLYWHEEL("", "flywheel backend DEFAULT"),
    /** Flywheel off: chunk meshes and block entity renderers only. Shot labels start with "nofw". */
    NO_FLYWHEEL("nofw", "flywheel backend off");

    private final String labelPrefix;
    private final String backendCommand;

    VisualPass(String labelPrefix, String backendCommand) {
        this.labelPrefix = labelPrefix;
        this.backendCommand = backendCommand;
    }

    /** Prefix of the shot labels of this pass ("" for none). */
    public String labelPrefix() {
        return labelPrefix;
    }

    /** Client command (without slash) that selects this pass's Flywheel backend. */
    public String backendCommand() {
        return backendCommand;
    }
}
