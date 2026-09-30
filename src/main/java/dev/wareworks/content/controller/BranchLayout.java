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
 * World mapping of <b>one straight branch</b> of a warehouse — what a player calls an aisle: its origin block, its
 * direction and its {@link AisleGeometry}, optionally with the aisle letter ({@code docs/warehouse-system.md} §1-2,
 * ADR-033).
 * <p>
 * Branch-local coordinates are {@code x} along the branch ({@code facing}), {@code y} up and the rack {@link Side}:
 * {@code rackPos = dock + facing·x + side·1 + up·y}, where {@link Side#LEFT} is {@code facing.getCounterClockWise()}
 * and {@link Side#RIGHT} is {@code facing.getClockWise()}. Positions on the branch line itself (its rails, and the dock
 * on branch {@value RackPosition#FIRST_BRANCH}) are no rack positions.
 * <p>
 * This is what the whole content layer talked to before M21 and it has not changed shape, which is why
 * {@link WarehouseMember#isAlignedWith} and {@link WarehouseMember#alignToAisle} are untouched: a branch is simply
 * named by {@link #branch()}, and for a warehouse of one aisle that name is {@value RackPosition#FIRST_BRANCH} and its
 * origin is the dock. Several branches together are a {@link WarehouseLayout}.
 * <p>
 * Immutable and cheap to create; the dock builds the first one from its current state
 * ({@code StackerCraneBlockEntity#layout()}).
 *
 * @param dock     the branch origin: position 0, level 0. On branch {@value RackPosition#FIRST_BRANCH} this is the
 *                 stacker crane dock itself, on any other branch the end nearer to it
 * @param facing   horizontal branch direction, from the origin towards the far end
 * @param geometry branch size
 * @param letter   aisle letter {@code 'A'..'Z'}, if assigned (by the controller)
 * @param branch   branch index inside its warehouse, {@value RackPosition#FIRST_BRANCH} for a warehouse of one aisle
 */
public record BranchLayout(BlockPos dock, Direction facing, AisleGeometry geometry, Optional<Character> letter,
                           int branch) {
    public BranchLayout {
        dock = Objects.requireNonNull(dock, "dock").immutable();
        Objects.requireNonNull(facing, "facing");
        if (!facing.getAxis().isHorizontal())
            throw new IllegalArgumentException("aisle direction must be horizontal: " + facing);
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(letter, "letter");
        if (letter.isPresent() && !StorageAddress.isValidAisle(letter.get()))
            throw new IllegalArgumentException("aisle letter must be A-Z: " + letter.get());
        if (branch < RackPosition.FIRST_BRANCH || branch > RackPosition.MAX_BRANCH)
            throw new IllegalArgumentException("branch must be in " + RackPosition.FIRST_BRANCH + ".."
                    + RackPosition.MAX_BRANCH + ": " + branch);
    }

    /** The first branch of a warehouse — the one at the dock — without an aisle letter. */
    public static BranchLayout of(BlockPos dock, Direction facing, AisleGeometry geometry) {
        return new BranchLayout(dock, facing, geometry, Optional.empty(), RackPosition.FIRST_BRANCH);
    }

    /** A further branch: {@code origin} is its end nearer the dock, {@code heading} points away from that end. */
    public static BranchLayout of(int branch, BlockPos origin, Direction heading, AisleGeometry geometry) {
        return new BranchLayout(origin, heading, geometry, Optional.empty(), branch);
    }

    public BranchLayout withLetter(char aisleLetter) {
        return new BranchLayout(dock, facing, geometry, Optional.of(aisleLetter), branch);
    }

    public BranchLayout withGeometry(AisleGeometry newGeometry) {
        return new BranchLayout(dock, facing, newGeometry, letter, branch);
    }

    /** The branch origin; the same block as {@link #dock()}, named for what it is on a branch that is not the first. */
    public BlockPos origin() {
        return dock;
    }

    /** The branch direction; the same as {@link #facing()}, named for what it is on a branch that is not the first. */
    public Direction heading() {
        return facing;
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

    /**
     * World position of a rack position of <b>this</b> branch. A {@code BranchLayout} maps one branch, so a position of
     * another one has no meaning here (ADR-033): its branch is ignored, exactly as the bounds are. Ask a
     * {@link WarehouseLayout} when the position may be on any branch.
     */
    public BlockPos rackPos(RackPosition rack) {
        return rackPos(rack.x(), rack.y(), rack.side());
    }

    /**
     * The branch-local rack position of a world position, or empty if {@code pos} is not a rack position of this branch
     * (the branch line itself, outside the length or height, or more than one block to the side). A position that
     * belongs to a <b>neighbouring</b> branch of the same warehouse is not one of this branch's, which is what
     * {@link WarehouseLayout#candidates} exists to resolve.
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
        return Optional.of(new RackPosition(branch, along, dy, side.get()));
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
