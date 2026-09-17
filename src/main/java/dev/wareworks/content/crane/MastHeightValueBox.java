package dev.wareworks.content.crane;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Position of the dock's "Mast Height" value box ({@code docs/stacker-crane.md} §2.1): centred on the top face of the low
 * rail bed ({@link StackerCraneBlock#BED_HEIGHT_PIXELS}), the only face of the bed large enough for a value box. The side
 * faces of the bed are only a few pixels high, so they have none.
 * <p>
 * Like Create's {@code CenteredSideValueBoxTransform} (face at 16 px, box at 15.5 px), the box sits
 * {@value #FACE_INSET_PIXELS} px below the face it belongs to. While the crane is parked, its chassis stands over the box;
 * Create still shows the hover hint for the targeted bed.
 */
public class MastHeightValueBox extends ValueBoxTransform.Sided {
    /** Distance of the box below its face, in pixels (Create's convention for centred side boxes). */
    public static final float FACE_INSET_PIXELS = 0.5F;
    private static final float CENTER_PIXELS = 8.0F;

    @Override
    protected Vec3 getSouthLocation() {
        // Sided turns the south location to the top face: its z becomes the height.
        return VecHelper.voxelSpace(CENTER_PIXELS, CENTER_PIXELS, StackerCraneBlock.BED_HEIGHT_PIXELS - FACE_INSET_PIXELS);
    }

    @Override
    protected boolean isSideActive(BlockState state, Direction direction) {
        return direction == Direction.UP;
    }
}
