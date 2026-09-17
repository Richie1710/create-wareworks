package dev.wareworks.content.station;

import java.util.Objects;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Create mechanical arm interaction point type of exactly one warehouse station block
 * ({@code docs/warehouse-system.md} §3.2, M12).
 * <p>
 * A Create arm can only target a block that a registered {@link ArmInteractionPointType} accepts
 * ({@code ArmInteractionPointType#getPrimaryType}); there is no fallback to the item capability. Every station
 * therefore gets a type of its own ({@code WareworksArmInteractionPoints}), and because an arm saves the type id with
 * each of its points, what an arm may do at one station can change later without breaking arms saved in worlds.
 * <p>
 * The type only decides <b>which block</b> it accepts: the station block in every state (facing, powered, screen side)
 * and nothing else. What the arm may do there is the point's business ({@link WarehouseInputArmPoint},
 * {@link DeliveryStationArmPoint}). Default priority: no Create type accepts a Wareworks block, so there is nothing to
 * win against.
 * <p>
 * Common code, and safe on both sides: Create creates points on the client while a player selects targets with the arm
 * item, and again on the server from the placement packet and from saves.
 */
public class StationArmPointType extends ArmInteractionPointType {
    private final BlockEntry<? extends Block> block;
    private final PointFactory factory;

    /**
     * @param block   the one station block this type accepts
     * @param factory creates the point, e.g. a constructor reference
     */
    public StationArmPointType(BlockEntry<? extends Block> block, PointFactory factory) {
        this.block = Objects.requireNonNull(block, "block");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** Exactly the station block, in every state; only the state is read, never the level. */
    @Override
    public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
        return block.has(state);
    }

    @Override
    public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
        return factory.create(this, level, pos, state);
    }

    /**
     * Where an arm reaches for a station: the centre of the top face, like Create's
     * {@code AllArmInteractionPointTypes.TopFaceArmInteractionPoint}. Every station is a full block, and an arm standing
     * anywhere around a rack can reach its top, while the crane only ever reaches in from the aisle side.
     */
    static Vec3 topFaceCentre(BlockPos pos) {
        return Vec3.atLowerCornerOf(pos).add(0.5, 1, 0.5);
    }

    /** Creates the interaction point of a {@link StationArmPointType}. */
    @FunctionalInterface
    public interface PointFactory {
        ArmInteractionPoint create(ArmInteractionPointType type, Level level, BlockPos pos, BlockState state);
    }
}
