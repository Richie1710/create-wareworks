package dev.wareworks.content.crane;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.CranePose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * What makes a stacker crane one crane of a warehouse with several (issue #14, ADR-075, {@code docs/stacker-crane.md}
 * §4.8): which controller it works for, the warehouse frame its poses are named in, where its own dock stands in that
 * frame and its number.
 * <p>
 * The <b>anchor</b> is the warehouse's own (primary) dock: branch 0 starts there, and every pose, route and rack
 * position of a crane of that warehouse is measured from it. For the crane at the primary dock the anchor is its own
 * block; for a <b>member</b> — a dock without a controller of its own that faces into the warehouse's rails — it is the
 * other dock, which is why a member renders and plays its sounds with the offset between the two.
 * <p>
 * A crane of a warehouse with <b>one</b> crane has no membership at all and writes none, so every save up to 0.17.1
 * reads exactly as it was written ({@link #TAG} absent).
 *
 * @param controller   the warehouse controller the crane works for
 * @param anchor       the warehouse's own dock: position 0 of branch 0
 * @param anchorFacing that dock's facing, i.e. the heading of branch 0
 * @param siteBranch   the branch the crane's own dock lies on, in the warehouse's numbering
 * @param siteX        the position of the crane's own dock on that branch
 * @param number       the crane's number: 1 at the warehouse's own dock, then 2, 3, ... in discovery order
 * @param craneCount   how many cranes the warehouse has, this one included (at least 2 while a membership exists)
 */
public record CraneMembership(BlockPos controller, BlockPos anchor, Direction anchorFacing, int siteBranch, int siteX,
                              int number, int craneCount) {
    /** The NBT key of the membership and the crane's traffic claim, on disk and in client packets. */
    public static final String TAG = "Traffic";

    private static final String CONTROLLER = "Controller";
    private static final String ANCHOR = "Anchor";
    private static final String ANCHOR_FACING = "AnchorFacing";
    private static final String DOCK_BRANCH = "DockB";
    private static final String DOCK_X = "DockX";
    private static final String NUMBER = "Number";
    private static final String CRANES = "Cranes";

    public CraneMembership {
        controller = Objects.requireNonNull(controller, "controller").immutable();
        anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        Objects.requireNonNull(anchorFacing, "anchorFacing");
        if (!anchorFacing.getAxis().isHorizontal())
            throw new IllegalArgumentException("a dock faces horizontally: " + anchorFacing);
        if (siteBranch < RackPosition.FIRST_BRANCH || siteBranch > RackPosition.MAX_BRANCH || siteX < 0)
            throw new IllegalArgumentException("not a dock site: " + siteBranch + "/" + siteX);
        if (number < 1)
            throw new IllegalArgumentException("crane numbers start at 1: " + number);
        craneCount = Math.max(craneCount, number);
    }

    /** The membership of the crane at the warehouse's own dock {@code dock}, in a warehouse of {@code craneCount}. */
    public static CraneMembership primary(BlockPos controller, BlockPos dock, Direction facing, int craneCount) {
        return new CraneMembership(controller, dock, facing, RackPosition.FIRST_BRANCH, 0, 1, craneCount);
    }

    /** Whether this crane's dock is not the warehouse's own: a member dock, crane 2 or later. */
    public boolean isMember() {
        return number > 1;
    }

    /** The pose a crane of this dock parks in: its own dock block, arm retracted, facing the way its branch runs. */
    public CranePose dockPose(double yaw) {
        return new CranePose(siteBranch, siteX, 0.0, CranePose.RETRACTED, CranePose.DEFAULT_SIDE, yaw);
    }

    /** The same crane in a warehouse of another size. */
    public CraneMembership withCraneCount(int count) {
        return new CraneMembership(controller, anchor, anchorFacing, siteBranch, siteX, number, count);
    }

    /** NBT form: the frame only; the claim is written beside it by the dock ({@link CraneTrafficClaim}). */
    public void write(CompoundTag tag) {
        tag.putLong(CONTROLLER, controller.asLong());
        tag.putInt(NUMBER, number);
        tag.putInt(CRANES, craneCount);
        // The crane at the warehouse's own dock is its own anchor, so the frame of a primary is four bytes shorter.
        if (isMember()) {
            tag.putLong(ANCHOR, anchor.asLong());
            tag.putString(ANCHOR_FACING, anchorFacing.getSerializedName());
            tag.putInt(DOCK_BRANCH, siteBranch);
            tag.putInt(DOCK_X, siteX);
        }
    }

    /**
     * The membership a tag describes, or empty for a tag without one. Untrusted data: anything that does not describe a
     * frame is no membership, and the crane is then the crane of its own dock it was before.
     *
     * @param self       the dock reading the tag
     * @param selfFacing its facing
     */
    public static Optional<CraneMembership> read(CompoundTag tag, BlockPos self, Direction selfFacing) {
        if (!tag.contains(CONTROLLER, Tag.TAG_LONG) || !tag.contains(NUMBER, Tag.TAG_INT))
            return Optional.empty();
        try {
            int number = tag.getInt(NUMBER);
            BlockPos controller = BlockPos.of(tag.getLong(CONTROLLER));
            int cranes = tag.getInt(CRANES);
            if (number <= 1)
                return Optional.of(primary(controller, self, selfFacing, Math.max(2, cranes)));
            Direction facing = Direction.byName(tag.getString(ANCHOR_FACING));
            if (facing == null || !tag.contains(ANCHOR, Tag.TAG_LONG))
                return Optional.empty();
            return Optional.of(new CraneMembership(controller, BlockPos.of(tag.getLong(ANCHOR)), facing,
                    tag.getInt(DOCK_BRANCH), tag.getInt(DOCK_X), number, Math.max(2, cranes)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
