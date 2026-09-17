package dev.wareworks.core.job;

/**
 * Per-tick speeds of the three crane axes ({@code docs/stacker-crane.md} §5), usually from
 * {@link CraneKinematics#speeds}.
 *
 * @param vx travel speed along the aisle, in positions (blocks) per tick
 * @param vy lift speed, in levels (blocks) per tick
 * @param va arm speed, in full extensions per tick
 */
public record CraneSpeeds(double vx, double vy, double va) {
    /** No motion at all (no rotation, overstressed). */
    public static final CraneSpeeds STOPPED = new CraneSpeeds(0.0, 0.0, 0.0);

    public CraneSpeeds {
        requireSpeed("vx", vx);
        requireSpeed("vy", vy);
        requireSpeed("va", va);
    }

    /** Whether no axis moves; the crane is paused ({@code docs/stacker-crane.md} §4). */
    public boolean isStopped() {
        return vx == 0.0 && vy == 0.0 && va == 0.0;
    }

    private static void requireSpeed(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0)
            throw new IllegalArgumentException(name + " must be a finite, non-negative speed: " + value);
    }
}
