package dev.wareworks.content.controller;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * World mapping of one aisle: the dock position, the aisle direction and the {@link AisleGeometry}, optionally with the
 * aisle letter ({@code docs/warehouse-system.md} §1-2).
 * <p>
 * Aisle-local coordinates are {@code x} along the aisle ({@code facing}), {@code y} up and the rack {@link Side}:
 * {@code rackPos = dock + facing·x + side·1 + up·y}, where {@link Side#LEFT} is {@code facing.getCounterClockWise()}
 * and {@link Side#RIGHT} is {@code facing.getClockWise()}. Positions on the aisle line itself (the dock and its rails)
 * are no rack positions.
 * <p>
 * Immutable and cheap to create; the dock builds it from its current state ({@code StackerCraneBlockEntity#layout()}).
 *
 * @param dock     position of the stacker crane dock (aisle position 0, level 0)
 * @param facing   horizontal aisle direction (the dock's facing)
 * @param geometry aisle size
 * @param letter   aisle letter {@code 'A'..'Z'}, if assigned (by the controller)
 */
public record AisleLayout(BlockPos dock, Direction facing, AisleGeometry geometry, Optional<Character> letter) {
    public AisleLayout {
        dock = Objects.requireNonNull(dock, "dock").immutable();
        Objects.requireNonNull(facing, "facing");
        if (!facing.getAxis().isHorizontal())
            throw new IllegalArgumentException("aisle direction must be horizontal: " + facing);
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(letter, "letter");
        if (letter.isPresent() && !StorageAddress.isValidAisle(letter.get()))
            throw new IllegalArgumentException("aisle letter must be A-Z: " + letter.get());
    }

    /** A layout without an aisle letter. */
    public static AisleLayout of(BlockPos dock, Direction facing, AisleGeometry geometry) {
        return new AisleLayout(dock, facing, geometry, Optional.empty());
    }

    public AisleLayout withLetter(char aisleLetter) {
        return new AisleLayout(dock, facing, geometry, Optional.of(aisleLetter));
    }

    public AisleLayout withGeometry(AisleGeometry newGeometry) {
        return new AisleLayout(dock, facing, newGeometry, letter);
    }

    /** World direction of a rack side: LEFT is {@code facing.getCounterClockWise()}, RIGHT {@code getClockWise()}. */
    public Direction sideDirection(Side side) {
        return switch (side) {
            case LEFT -> facing.getCounterClockWise();
            case RIGHT -> facing.getClockWise();
        };
    }

    /** Position {@code x} on the aisle line at dock height: the dock for {@code x = 0}, otherwise the x-th rail. */
    public BlockPos aislePos(int x) {
        return dock.relative(facing, x);
    }

    /**
     * World position of a rack position: {@code dock + facing·x + side·1 + up·y}. No bounds check, so targets can be
     * computed for positions outside a shrunken geometry; use {@link #worldToLocal} or
     * {@link AisleGeometry#contains(RackPosition)} to validate.
     */
    public BlockPos rackPos(int x, int y, Side side) {
        return dock.relative(facing, x).relative(sideDirection(side)).above(y);
    }

    public BlockPos rackPos(RackPosition rack) {
        return rackPos(rack.x(), rack.y(), rack.side());
    }

    /**
     * The aisle-local rack position of a world position, or empty if {@code pos} is not a rack position of this aisle
     * (aisle line, outside the length or height, or more than one block to the side).
     */
    public Optional<RackPosition> worldToLocal(BlockPos pos) {
        int dx = pos.getX() - dock.getX();
        int dy = pos.getY() - dock.getY();
        int dz = pos.getZ() - dock.getZ();
        int along = dx * facing.getStepX() + dz * facing.getStepZ();
        Direction right = facing.getClockWise();
        int lateral = dx * right.getStepX() + dz * right.getStepZ();
        Optional<Side> side = Side.fromLateralOffset(lateral);
        if (side.isEmpty() || !geometry.contains(along, dy))
            return Optional.empty();
        return Optional.of(new RackPosition(along, dy, side.get()));
    }

    /** Whether {@code pos} is one of the rack positions of this aisle. */
    public boolean isRackPosition(BlockPos pos) {
        return worldToLocal(pos).isPresent();
    }

    /** The address of a rack position in this aisle, if the aisle has a letter. */
    public Optional<StorageAddress> address(RackPosition rack) {
        return letter.map(aisleLetter -> StorageAddress.of(aisleLetter, rack));
    }

    /** The address of a world position, if it is a rack position and the aisle has a letter. */
    public Optional<StorageAddress> addressOf(BlockPos pos) {
        return letter.flatMap(aisleLetter -> worldToLocal(pos).map(rack -> StorageAddress.of(aisleLetter, rack)));
    }

    /**
     * Full-block bounds of all rack positions: {@code positionCount} blocks along the aisle, 3 blocks across (both rack
     * planes and the aisle line between them) and {@code height} blocks up from the dock level.
     */
    public AABB bounds() {
        return AABB.encapsulatingFullBlocks(rackPos(0, 0, Side.LEFT),
                rackPos(geometry.length(), geometry.height() - 1, Side.RIGHT));
    }
}
