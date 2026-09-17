package dev.wareworks.core.job;

import java.util.ArrayList;
import java.util.List;

/**
 * Crane axis speeds from the kinetic speed ({@code docs/stacker-crane.md} §5):
 * <pre>
 * vx = min(|rpm| · travelBlocksPerTickPerRpm, maxBlocksPerTick)
 * vy = min(|rpm| · liftBlocksPerTickPerRpm,  maxBlocksPerTick)
 * va = min(|rpm| · armExtendPerTickPerRpm,   {@value #MAX_ARM_SPEED})
 * </pre>
 * Create's {@code KineticBlockEntity#getSpeed()} is already 0 without power and while overstressed, so no special cases
 * are needed: all speeds become 0 and the crane pauses. A non-finite rpm is treated as 0 (NaN) or capped (infinity).
 * Pure functions; the content layer copies the config values into {@link Params}.
 */
public final class CraneKinematics {
    /** The arm never extends faster than fully in one tick. */
    public static final double MAX_ARM_SPEED = 1.0;

    private CraneKinematics() {
    }

    /**
     * The speed factors from the server config ({@code crane.*}, {@code docs/warehouse-system.md} §9).
     *
     * @param travelBlocksPerTickPerRpm X speed per RPM
     * @param liftBlocksPerTickPerRpm   Y speed per RPM
     * @param armExtendPerTickPerRpm    arm speed per RPM, in full extensions
     * @param maxBlocksPerTick          hard cap for X and Y
     */
    public record Params(double travelBlocksPerTickPerRpm, double liftBlocksPerTickPerRpm, double armExtendPerTickPerRpm,
            double maxBlocksPerTick) {
        public Params {
            requireFactor("travelBlocksPerTickPerRpm", travelBlocksPerTickPerRpm);
            requireFactor("liftBlocksPerTickPerRpm", liftBlocksPerTickPerRpm);
            requireFactor("armExtendPerTickPerRpm", armExtendPerTickPerRpm);
            requireFactor("maxBlocksPerTick", maxBlocksPerTick);
        }

        private static void requireFactor(String name, double value) {
            if (!Double.isFinite(value) || value < 0.0)
                throw new IllegalArgumentException(name + " must be finite and not negative: " + value);
        }

        /**
         * Whether at least one factor is 0, which stops an axis at every rotation speed. A single stopped axis would
         * stall a crane mid-trip while the others keep moving, so the content layer treats such a configuration as
         * "the crane is paused" and says so ({@code docs/stacker-crane.md} §4.2).
         * <p>
         * The config range of the three per-RPM factors starts at 0 ({@code docs/warehouse-system.md} §9), so those
         * three really are reachable from {@code wareworks-server.toml}. {@code maxBlocksPerTick} is guarded at 0.01
         * there and is checked here only because this record is public core API that any caller can build with 0.
         */
        public boolean hasZeroFactor() {
            return travelBlocksPerTickPerRpm == 0.0 || liftBlocksPerTickPerRpm == 0.0 || armExtendPerTickPerRpm == 0.0
                    || maxBlocksPerTick == 0.0;
        }

        /** The names of the factors that are 0, in config order; empty when the crane can move at a non-zero rpm. */
        public List<String> zeroFactorNames() {
            List<String> names = new ArrayList<>(4);
            if (travelBlocksPerTickPerRpm == 0.0)
                names.add("crane.travelBlocksPerTickPerRpm");
            if (liftBlocksPerTickPerRpm == 0.0)
                names.add("crane.liftBlocksPerTickPerRpm");
            if (armExtendPerTickPerRpm == 0.0)
                names.add("crane.armExtendPerTickPerRpm");
            if (maxBlocksPerTick == 0.0)
                names.add("crane.maxBlocksPerTick");
            return names;
        }
    }

    /** The axis speeds for a kinetic speed of {@code rpm} (sign ignored). */
    public static CraneSpeeds speeds(Params params, double rpm) {
        double magnitude = Double.isNaN(rpm) ? 0.0 : Math.abs(rpm);
        return new CraneSpeeds(
                axisSpeed(magnitude, params.travelBlocksPerTickPerRpm(), params.maxBlocksPerTick()),
                axisSpeed(magnitude, params.liftBlocksPerTickPerRpm(), params.maxBlocksPerTick()),
                axisSpeed(magnitude, params.armExtendPerTickPerRpm(), MAX_ARM_SPEED));
    }

    private static double axisSpeed(double rpmMagnitude, double factor, double cap) {
        if (rpmMagnitude == 0.0 || factor == 0.0)
            return 0.0;
        return Math.min(rpmMagnitude * factor, cap);
    }
}
