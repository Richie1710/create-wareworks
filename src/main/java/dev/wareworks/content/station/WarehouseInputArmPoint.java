package dev.wareworks.content.station;

import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes.DepositOnlyArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import dev.wareworks.content.item.InsertOnlyItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mechanical arm interaction point of the warehouse input ({@code docs/warehouse-system.md} §3.2, M12): <b>deposit
 * only</b>, like Create's funnel point.
 * <p>
 * The arm puts items in through the input's item capability, the same {@link InsertOnlyItemHandler} funnels, chutes
 * and hoppers use, so the buffer's rules apply unchanged and the arm keeps what does not fit. It can never take
 * anything out: Create's {@link DepositOnlyArmInteractionPoint} offers no slots and extracts nothing, and a right-click
 * with the arm item cannot switch the point to "take". A saved point loads as a deposit point whatever mode its tag
 * names, so an edited or foreign save cannot turn the input into a source either. The arm reaches for the centre of the
 * top face ({@link StationArmPointType#topFaceCentre}).
 */
public class WarehouseInputArmPoint extends DepositOnlyArmInteractionPoint {
    public WarehouseInputArmPoint(ArmInteractionPointType type, Level level, BlockPos pos, BlockState state) {
        super(type, level, pos, state);
    }

    @Override
    protected Vec3 getInteractionPositionVector() {
        return StationArmPointType.topFaceCentre(pos);
    }

    /** Reads the saved point as Create does, then forces "deposit": the input is never a source. */
    @Override
    protected void deserialize(CompoundTag nbt, BlockPos anchor) {
        super.deserialize(nbt, anchor);
        mode = Mode.DEPOSIT;
    }
}
