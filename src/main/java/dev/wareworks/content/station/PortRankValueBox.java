package dev.wareworks.content.station;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Position of the warehouse port's <b>wrench-only</b> settings box ({@code docs/warehouse-system.md} §3.2, M17): the
 * same faces as the request filter slot — top, back and both sides, never the bottom and never the aisle side, where
 * the crane reaches into the opening — but a little higher and smaller than the filter slot.
 * <p>
 * <b>Why the two boxes may share a face at all.</b> Exactly one of them is ever eligible: the port box is
 * {@code onlyVisibleWithWrench()}, so Create's {@code ValueSettingsInputHandler} and {@code ScrollValueRenderer} skip it
 * unless the main hand holds a wrench, and {@link RequestFilterBehaviour#mayInteract} refuses a player holding <b>the
 * Wrench</b>, so {@code FilteringRenderer} and the same input handler skip the filter slot exactly then. One sentence for
 * the player: hold the Wrench to configure the port, anything else to set the filter. Nothing has to be measured, the
 * filter slot does not move, and no hit region is ambiguous.
 * <p>
 * <b>Create's own wrench, not every wrench.</b> Create splits the two predicates itself: {@code ScrollValueRenderer}
 * draws a {@code needsWrench} box only for {@code AllItems.WRENCH}, while {@code ValueSettingsInputHandler} accepts the
 * whole {@code c:tools/wrench} tag. The filter slot therefore matches the <b>renderer</b>: with another mod's wrench the
 * port box is not drawn, so the filter slot keeps its own box, and the invariant above is about Create's wrench. A
 * modded wrench that lands inside the undrawn port region still opens the port's board — the same as on Create's own
 * wrench-only boxes, e.g. the linear chassis range.
 * <p>
 * <b>Why it is not simply in the same place.</b> Create's {@code FilteringRenderer#renderOnBlockEntity} does not consult
 * {@code mayInteract}, so the filter <b>item</b> stays painted on the plate while a wrench is held — which is right, the
 * filter is part of the port's configuration. The box therefore sits {@value #CENTER_Y_PIXELS} px up instead of at the
 * plate's centre (y 8 px) and at scale {@value #SCALE} instead of Create's 0.5, so the rank text it draws never lands on
 * the filter item's sprite.
 */
public class PortRankValueBox extends ValueBoxTransform.Sided {
    /** Horizontal centre of the box, in pixels. */
    public static final float CENTER_X_PIXELS = 8.0F;
    /** Height of the box centre, in pixels: above the filter slot, still inside the recessed plate (y 5.5..13 px). */
    public static final float CENTER_Y_PIXELS = 11.5F;
    /** Distance in front of the face the box belongs to, in pixels (Create's convention for centred side boxes). */
    public static final float FACE_INSET_PIXELS = 0.5F;
    /** Box scale: Create hit-tests within half of it, i.e. a 3.2 px sphere, which stays clear of the frame at y 13 px. */
    public static final float SCALE = 0.4F;
    private static final float BLOCK_PIXELS = 16.0F;

    @Override
    protected Vec3 getSouthLocation() {
        return VecHelper.voxelSpace(CENTER_X_PIXELS, CENTER_Y_PIXELS, BLOCK_PIXELS - FACE_INSET_PIXELS);
    }

    @Override
    public float getScale() {
        return SCALE;
    }

    /** Top, back and both sides: not the bottom, and not the aisle side the crane reaches through. */
    @Override
    protected boolean isSideActive(BlockState state, Direction direction) {
        return direction != Direction.DOWN
                && direction != state.getOptionalValue(WarehouseStationBlock.FACING).orElse(null);
    }
}
