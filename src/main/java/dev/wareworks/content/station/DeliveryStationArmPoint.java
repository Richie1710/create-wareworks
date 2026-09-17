package dev.wareworks.content.station;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import dev.wareworks.content.item.ExtractOnlyItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mechanical arm interaction point of the stations the crane delivers into ({@link WarehouseDeliveryStationBlockEntity}:
 * warehouse output, warehouse terminal and warehouse production station; {@code docs/warehouse-system.md} §3.2, M12):
 * <b>take only</b>.
 * <p>
 * The arm takes items out through the station's item capability, the same {@link ExtractOnlyItemHandler} funnels, chutes
 * and hoppers pull from, so an arm emptying a production station advances its order exactly like a funnel would. It
 * never puts anything in: {@link #insert} returns the offered stack unchanged without asking the capability, the mode is
 * "take" from construction, a right-click with the arm item cannot switch it, and a saved point loads as "take" even when
 * its tag says "deposit" (an edited or foreign save). The arm reaches for the centre of the top face
 * ({@link StationArmPointType#topFaceCentre}).
 * <p>
 * One class serves three types on purpose: the stations share their automation surface today, while each keeps its own
 * type id, so a station that needs different arm behaviour later gets a subclass without breaking saved arms.
 */
public class DeliveryStationArmPoint extends ArmInteractionPoint {
    public DeliveryStationArmPoint(ArmInteractionPointType type, Level level, BlockPos pos, BlockState state) {
        super(type, level, pos, state);
        mode = Mode.TAKE;
    }

    /** No-op: the point stays "take", so a right-click with the arm item only selects or keeps it. */
    @Override
    public void cycleMode() {
    }

    @Override
    protected Vec3 getInteractionPositionVector() {
        return StationArmPointType.topFaceCentre(pos);
    }

    /** Refuses everything: returns {@code stack} itself, without touching the station. */
    @Override
    public ItemStack insert(ArmBlockEntity armBlockEntity, ItemStack stack, boolean simulate) {
        return stack;
    }

    /** Reads the saved point as Create does, then forces "take": the station is never a destination. */
    @Override
    protected void deserialize(CompoundTag nbt, BlockPos anchor) {
        super.deserialize(nbt, anchor);
        mode = Mode.TAKE;
    }
}
