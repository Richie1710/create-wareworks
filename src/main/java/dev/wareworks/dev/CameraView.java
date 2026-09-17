package dev.wareworks.dev;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A fixed camera position of a scenario: the eye position and the point looked at, both relative to the lower corner of
 * the scene origin block.
 *
 * @param label  short name used in shot labels (e.g. "side")
 * @param eye    eye position relative to the origin
 * @param target looked-at point relative to the origin
 */
public record CameraView(String label, Vec3 eye, Vec3 target) {
    private static final float YAW_OFFSET_DEGREES = 90.0F;

    public CameraView {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(eye, "eye");
        Objects.requireNonNull(target, "target");
    }

    public static CameraView of(String label, double eyeX, double eyeY, double eyeZ, double targetX, double targetY,
            double targetZ) {
        return new CameraView(label, new Vec3(eyeX, eyeY, eyeZ), new Vec3(targetX, targetY, targetZ));
    }

    /**
     * The player placement for this view: feet position (eye minus {@code eyeHeight}) and the rotation that looks at the
     * target, computed like {@code Entity#lookAt}.
     */
    Placement placement(BlockPos origin, float eyeHeight) {
        Vec3 base = Vec3.atLowerCornerOf(origin);
        Vec3 worldEye = base.add(eye);
        Vec3 direction = base.add(target).subtract(worldEye);
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float pitch = Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(direction.y, horizontal)));
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(direction.z, direction.x)) - YAW_OFFSET_DEGREES);
        return new Placement(worldEye.x, worldEye.y - eyeHeight, worldEye.z, yaw, pitch);
    }

    /** Absolute feet position and rotation of the camera player. */
    record Placement(double x, double y, double z, float yaw, float pitch) {
        Vec3 feet() {
            return new Vec3(x, y, z);
        }
    }
}
