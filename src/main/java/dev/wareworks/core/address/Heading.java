package dev.wareworks.core.address;

/**
 * Horizontal direction of a warehouse branch, in the world's own axes but without a Minecraft type
 * ({@code docs/warehouse-system.md} §1, ADR-033).
 * <p>
 * {@link #NORTH} is {@code -Z}, {@link #EAST} is {@code +X}, {@link #SOUTH} is {@code +Z} and {@link #WEST} is
 * {@code -X}, and the declaration order is clockwise seen from above, so {@link #right()} is one step in that order.
 * This mirrors Minecraft's horizontal {@code Direction} exactly, which is what lets the content layer convert between
 * the two by ordinal-free name ({@code content.controller.Headings}) while {@code core.*} stays pure Java.
 */
public enum Heading {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    /** The axis a heading runs along; {@code core}'s stand-in for {@code Direction.Axis}. */
    public enum Axis {
        X,
        Z
    }

    private static final Heading[] CLOCKWISE = values();

    private final int stepX;
    private final int stepZ;

    Heading(int stepX, int stepZ) {
        this.stepX = stepX;
        this.stepZ = stepZ;
    }

    /** Step along the world X axis: {@code +1} east, {@code -1} west, {@code 0} otherwise. */
    public int stepX() {
        return stepX;
    }

    /** Step along the world Z axis: {@code +1} south, {@code -1} north, {@code 0} otherwise. */
    public int stepZ() {
        return stepZ;
    }

    public Axis axis() {
        return stepX != 0 ? Axis.X : Axis.Z;
    }

    /** The heading a quarter turn clockwise seen from above (north → east). */
    public Heading right() {
        return CLOCKWISE[(ordinal() + 1) % CLOCKWISE.length];
    }

    /** The heading a quarter turn counter-clockwise seen from above (north → west). */
    public Heading left() {
        return CLOCKWISE[(ordinal() + CLOCKWISE.length - 1) % CLOCKWISE.length];
    }

    public Heading opposite() {
        return CLOCKWISE[(ordinal() + 2) % CLOCKWISE.length];
    }

    /** Whether {@code other} runs along the other axis — the only way two branches of one network can meet. */
    public boolean isPerpendicularTo(Heading other) {
        return axis() != other.axis();
    }

    /**
     * The component of {@code (dx, dz)} along this heading: how far the offset goes forward (negative: backward).
     */
    public int along(int dx, int dz) {
        return dx * stepX + dz * stepZ;
    }

    /**
     * The component of {@code (dx, dz)} towards {@link #right()}: {@code -1} is the {@link Side#LEFT} rack plane,
     * {@code +1} the {@link Side#RIGHT} one ({@link Side#lateralOffset()}).
     */
    public int lateral(int dx, int dz) {
        Heading right = right();
        return dx * right.stepX + dz * right.stepZ;
    }

    /**
     * Quarter turns clockwise from north: {@code 0} north, {@code 1} east, {@code 2} south, {@code 3} west. This is
     * the unit the crane's yaw is measured in ({@code core.crane.CranePose#yaw()}), so that a turn between two
     * perpendicular branches is always exactly one of them.
     */
    public int quarterTurns() {
        return ordinal();
    }

    /** The heading {@code quarterTurns} quarter turns clockwise from north; any integer, wrapping. */
    public static Heading fromQuarterTurns(int quarterTurns) {
        int index = quarterTurns % CLOCKWISE.length;
        return CLOCKWISE[index < 0 ? index + CLOCKWISE.length : index];
    }

    /** The heading with these steps, or {@code null} if they are not one horizontal step. */
    public static Heading of(int stepX, int stepZ) {
        for (Heading heading : CLOCKWISE) {
            if (heading.stepX == stepX && heading.stepZ == stepZ)
                return heading;
        }
        return null;
    }
}
