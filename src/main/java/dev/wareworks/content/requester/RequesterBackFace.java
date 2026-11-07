package dev.wareworks.content.requester;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The two spots on the <b>back</b> of a Schematicannon Requester (issue #45, ADR-080) — the face a player stands at,
 * opposite the cannon — in the block's own pixels of {@code models/block/schematicannon_requester/block.json}, which is
 * authored with the cannon side facing north:
 * <ul>
 * <li>{@link Cradle}: the industrial iron cradle on the left half (x 2..9, y 3..11 px, its plate at z 14.5 and its lip
 * reaching to z 16), where {@code client.render.SchematicannonRequesterRenderer} stands the remote upright on the
 * lip, its screen outwards;</li>
 * <li>{@link Grace}: the grace value box on the right half (x 9..15 px), small enough that it never covers the cradle,
 * so the remote stays visible while the box is shown.</li>
 * </ul>
 * Both are Create's {@link ValueBoxTransform.Sided}, whose south location is turned onto the back face by the same
 * proper rotations the blockstate turns the model with (about Y for the four horizontal facings, about X for up and
 * down), so a point of the model and its spot stay together in all six facings.
 */
public final class RequesterBackFace {
    private RequesterBackFace() {
    }

    /** The face opposite the cannon: where a player stands, the cradle and the grace box. */
    public static Direction of(BlockState state) {
        return state.getOptionalValue(SchematicannonRequesterBlock.FACING).orElse(Direction.NORTH).getOpposite();
    }

    /** Create's value box for the grace on the back face, right of the cradle. */
    public static final class Grace extends ValueBoxTransform.Sided {
        /** Centre of the box in block pixels, as seen on the south face (x to the viewer's right). */
        public static final float CENTER_X_PIXELS = 12.0F;
        public static final float CENTER_Y_PIXELS = 7.0F;
        /** Half a pixel in front of the body's back face at z 14 px, Create's inset for side boxes. */
        public static final float DEPTH_PIXELS = 14.5F;
        /** A 6 px box (Create hit-tests within half of it, 3 px): x 9..15 px, clear of the cradle and the body's edge. */
        public static final float SCALE = 0.375F;

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(CENTER_X_PIXELS, CENTER_Y_PIXELS, DEPTH_PIXELS);
        }

        @Override
        public float getScale() {
            return SCALE;
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            return direction == of(state);
        }
    }

    /**
     * Where the remote stands in the cradle. {@code ValueBoxRenderer#renderItemIntoValueBox} draws a 3D item model at
     * the full box, so at this scale the remote (6 x 14 px of its own model, antenna included) comes out 3 x 7 px,
     * centred on the cradle's plate and standing on its lip.
     */
    public static final class Cradle extends ValueBoxTransform.Sided {
        public static final float CENTER_X_PIXELS = 5.5F;
        /** The remote's model reaches 8 px below its centre and 6 above, so this puts its foot on the lip at y 4 px. */
        public static final float CENTER_Y_PIXELS = 8.1F;
        /** Between the plate (z 14.5 px) and the lip's front (z 16 px); the model is 2.25 px deep before scaling. */
        public static final float DEPTH_PIXELS = 15.2F;
        public static final float SCALE = 0.5F;

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(CENTER_X_PIXELS, CENTER_Y_PIXELS, DEPTH_PIXELS);
        }

        @Override
        public float getScale() {
            return SCALE;
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            return direction == of(state);
        }

        /** Turns this spot onto the back face of {@code state}. */
        public Cradle onBackOf(BlockState state) {
            fromSide(of(state));
            return this;
        }

        @Override
        public boolean shouldRender(LevelAccessor level, BlockPos pos, BlockState state) {
            return !state.isAir();
        }
    }
}
