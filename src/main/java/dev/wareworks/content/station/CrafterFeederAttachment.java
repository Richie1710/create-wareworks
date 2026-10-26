package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which production station a Warehouse Crafter Feeder serves (issue #34, ADR-067 D2): the one whose <b>back</b> touches
 * the feeder.
 * <p>
 * A station faces the aisle ({@link WarehouseProductionBlock#FACING}), so its back is the side the player's machine
 * stands on. A station in direction {@code d} of the feeder has its back on the feeder exactly when it faces {@code d}
 * itself — away from the feeder, towards its aisle. The feeder looks for that on its five faces other than the one
 * towards its Crafter, so it may stand straight behind the station (station → feeder → Crafter) or face along the rack
 * wall with the Crafter behind the next rack position, which is where a Warehouse Input can stand in front of the
 * Crafter's output.
 * <p>
 * Exactly one such station or none: a feeder between the backs of two stations refuses, because it could not tell
 * whose patterns it lays ({@link Kind#TWO}). Block states only, so the answer costs five state reads and no block entity
 * lookup; a neighbour in a chunk that is not loaded makes the whole answer {@link Kind#UNLOADED}, never a guess.
 */
public final class CrafterFeederAttachment {
    /** What the five faces showed. */
    public enum Kind {
        /** No production station's back touches the feeder. */
        NONE,
        /** Exactly one does: {@link Result#station()}. */
        ONE,
        /** Two or more do. */
        TWO,
        /** A neighbour is not loaded, so nothing can be said. */
        UNLOADED
    }

    /**
     * The answer.
     *
     * @param kind    what was found
     * @param station the station's position for {@link Kind#ONE}, empty otherwise
     */
    public record Result(Kind kind, Optional<BlockPos> station) {
        public Result {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(station, "station");
        }

        static final Result NONE = new Result(Kind.NONE, Optional.empty());
        static final Result TWO = new Result(Kind.TWO, Optional.empty());
        static final Result UNLOADED = new Result(Kind.UNLOADED, Optional.empty());
    }

    private CrafterFeederAttachment() {
    }

    /**
     * The station a feeder at {@code feeder} facing {@code facing} (towards its Crafter) serves.
     */
    public static Result resolve(Level level, BlockPos feeder, Direction facing) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(feeder, "feeder");
        Objects.requireNonNull(facing, "facing");
        BlockPos found = null;
        for (Direction side : Direction.values()) {
            if (side == facing)
                continue;
            BlockPos neighbour = feeder.relative(side);
            if (!level.isLoaded(neighbour))
                return Result.UNLOADED;
            if (!backTouches(level.getBlockState(neighbour), side))
                continue;
            if (found != null)
                return Result.TWO;
            found = neighbour.immutable();
        }
        return found == null ? Result.NONE : new Result(Kind.ONE, Optional.of(found));
    }

    /**
     * Whether {@code state}, standing in direction {@code side} of a feeder, is a production station whose back is on
     * that feeder: it faces {@code side}, away from it.
     */
    public static boolean backTouches(@Nullable BlockState state, Direction side) {
        return state != null && state.getBlock() instanceof WarehouseProductionBlock
                && state.hasProperty(WarehouseProductionBlock.FACING)
                && state.getValue(WarehouseProductionBlock.FACING) == side;
    }
}
