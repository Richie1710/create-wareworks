package dev.wareworks.content.storage;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Position of the warehouse interface's store filter slot ({@code docs/warehouse-system.md} §3.1.1, ADR-021): centred on
 * the <b>aisle side</b> ({@code FACING.getOpposite()}) in the lower half of the framed plate, below the crane's arm port.
 * <p>
 * The aisle side is the only face of an interface a player can use, and putting the slot anywhere else would break a
 * different interaction, because Create's {@code ValueSettingsInputHandler} cancels a right-click that hits a value box
 * with {@code SUCCESS}, so the block's own interaction never runs:
 * <ul>
 * <li>the {@code FACING} side touches the attached inventory and cannot be clicked at all;</li>
 * <li>the two lateral sides carry the row-building placement rule ("click the side of the previous interface to copy its
 * facing", §3.1.1), which a value box there would swallow;</li>
 * <li>top and bottom are where the wrench rotates the block ({@code IWrenchable});</li>
 * <li>in a real rack wall all four of those faces are covered by neighbours anyway.</li>
 * </ul>
 * <b>Clear of the arm port.</b> The port the telescopic arm reaches through is at y 9..13 px
 * ({@code models/block/warehouse_interface/block.json}, {@code stacker-crane.md} §7.1). The box sits at y
 * {@value #CENTER_Y_PIXELS} px and Create draws and hit-tests it within half its scale (4 px), i.e. y 1..9 px, so
 * neither the drawn filter item nor the click sphere ever reaches into the arm's path. The remaining face area still
 * places blocks normally.
 */
public class StorageFilterValueBox extends ValueBoxTransform.Sided {
    /** Horizontal centre of the plate, in pixels. */
    public static final float CENTER_X_PIXELS = 8.0F;
    /** Height of the box centre, in pixels: half its scale below the arm port at y 9 px. */
    public static final float CENTER_Y_PIXELS = 5.0F;
    /** Distance of the box in front of the face it belongs to, in pixels (Create's convention for centred side boxes). */
    public static final float FACE_INSET_PIXELS = 0.5F;
    private static final float BLOCK_PIXELS = 16.0F;

    @Override
    protected Vec3 getSouthLocation() {
        return VecHelper.voxelSpace(CENTER_X_PIXELS, CENTER_Y_PIXELS, BLOCK_PIXELS - FACE_INSET_PIXELS);
    }

    @Override
    protected boolean isSideActive(BlockState state, Direction direction) {
        return state.getOptionalValue(WarehouseInterfaceBlock.FACING)
                .map(facing -> direction == facing.getOpposite())
                .orElse(false);
    }
}
