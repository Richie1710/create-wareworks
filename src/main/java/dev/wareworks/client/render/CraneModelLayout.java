package dev.wareworks.client.render;

/**
 * Dimensions of the stacker crane partial models that {@link StackerCraneRenderer} relies on, and the pose math that
 * places the parts ({@code docs/stacker-crane.md} §7.1). Pure Java, so the model coherence can be checked by JUnit
 * ({@code CraneModelLayoutTest} reads the model JSON files against these values).
 * <p>
 * Units: {@code *_PX} values are model pixels (16 per block) in the model frame of {@link WareworksPartialModels}
 * (aisle direction north = -Z, right rack side east = +X, y = 0 at the floor of the crane's level); {@code *_BLOCKS}
 * values and the results of the methods are blocks.
 */
public final class CraneModelLayout {
    /** Model pixels per block. */
    public static final float PIXELS_PER_BLOCK = 16.0F;
    /** Centre of a block in model pixels; the pivot of centred parts (wheel, cog). */
    public static final float BLOCK_CENTER_PX = 8.0F;

    // --- base -----------------------------------------------------------------------------------------------------

    /** Top of the warehouse rail head, where the wheels touch (same profile as {@code warehouse_rail}). */
    public static final float RAIL_TOP_PX = 3.0F;
    /**
     * Wheel radius; the wheel model is a disc of this radius around the block centre, plus a copy turned by 45° whose
     * corners reach {@code √2} times as far. Small enough that the wheels stay below {@link #CHASSIS_TOP_PX} and inside the
     * chassis ends at every angle (no face in the chassis top plane, nothing poking through it).
     */
    public static final float WHEEL_RADIUS_PX = 2.0F;
    /** Height of the wheel axles: the wheels stand on the rail head. */
    public static final float WHEEL_AXLE_Y_PX = RAIL_TOP_PX + WHEEL_RADIUS_PX;
    /** Axle position of the front wheel (towards the aisle direction). */
    public static final float FRONT_WHEEL_Z_PX = 3.5F;
    /** Axle position of the rear wheel (towards the dock end). */
    public static final float REAR_WHEEL_Z_PX = 12.5F;
    /** Top of the chassis: the mast stands on it and the carriage rests on it at level 0. */
    public static final float CHASSIS_TOP_PX = 8.0F;

    /** Scale of Create's shaftless cogwheel (18 px across) used as the drive cog on the chassis front. */
    public static final float DRIVE_COG_SCALE = 0.35F;
    /** Centre of the drive cog; it turns about the aisle axis in front of the chassis. */
    public static final float DRIVE_COG_Y_PX = 6.5F;
    public static final float DRIVE_COG_Z_PX = 0.0F;

    // --- mast -----------------------------------------------------------------------------------------------------

    /** Bottom of the first mast segment (on the chassis). */
    public static final float MAST_BASE_Y_PX = CHASSIS_TOP_PX;

    // --- carriage and arm -----------------------------------------------------------------------------------------

    /** Top of the carriage guide frame, where the hoist belt is attached. */
    public static final float CARRIAGE_TOP_Y_PX = 15.0F;
    /** How far the inner stage (and the grabber) moves at full extension: into the rack position one block aside. */
    public static final double ARM_REACH_BLOCKS = 1.0;
    /** Share of the extension the outer stage moves. */
    public static final double OUTER_STAGE_SHARE = 0.5;
    /** Front face of the retracted grabber, on the extension side; at full reach it stops just before the inventory. */
    public static final float GRABBER_FRONT_PX = 15.5F;

    // --- held items -----------------------------------------------------------------------------------------------

    /** Top of the inner stage, where held items lie. */
    public static final float ITEM_REST_Y_PX = 11.5F;
    /** Item model scale (a flat item is then 5.4 px across). */
    public static final float ITEM_SCALE = 0.34F;
    /** Thickness of a flat (generated) item model in its fixed display transform, before {@link #ITEM_SCALE}. */
    public static final float FLAT_ITEM_THICKNESS_PX = 1.0F;
    /** Edge of a block item model in its fixed display transform (half a block), before {@link #ITEM_SCALE}. */
    public static final float BLOCK_ITEM_SIZE_PX = 8.0F;
    /** Item positions along the arm (retracted, extension towards +X): the first held type next to the grabber. */
    public static final float[] ITEM_SLOT_X_PX = {9.5F, 4.0F};
    /** Item position across the arm (its centre line). */
    public static final float ITEM_Z_PX = BLOCK_CENTER_PX;
    /** Held amount from which a second item model is stacked on the first. */
    public static final long SECOND_ITEM_COPY_MIN_COUNT = 32;
    /** Height of one stacked copy of a flat item and of a block item. */
    public static final float FLAT_ITEM_STACK_PX = 0.6F;
    public static final float BLOCK_ITEM_STACK_PX = 2.8F;
    /** Yaw between stacked copies, so a pile does not look like one item. */
    public static final float ITEM_STACK_YAW_DEGREES = 20.0F;

    private CraneModelLayout() {
    }

    /** Blocks the outer stage is moved towards the arm side at {@code arm} extension ({@code 0..1}). */
    public static double outerStageOffset(double arm) {
        return arm * ARM_REACH_BLOCKS * OUTER_STAGE_SHARE;
    }

    /** Blocks the inner stage and the grabber are moved towards the arm side at {@code arm} extension ({@code 0..1}). */
    public static double innerStageOffset(double arm) {
        return arm * ARM_REACH_BLOCKS;
    }

    /**
     * Wheel rotation in radians about the +X axis after travelling {@code travelBlocks} along the aisle: rolling towards the
     * aisle direction (-Z) turns the wheel top forwards.
     */
    public static double wheelAngle(double travelBlocks) {
        return -travelBlocks * PIXELS_PER_BLOCK / WHEEL_RADIUS_PX;
    }

    /** Bottom of the mast cap above the crane's floor, in blocks, for a mast of {@code mastHeight} segments. */
    public static double mastTopY(int mastHeight) {
        return MAST_BASE_Y_PX / PIXELS_PER_BLOCK + mastHeight;
    }

    /** Bottom of the hoist belt (top of the carriage) for the carriage at level {@code liftY}, in blocks. */
    public static double hoistBeltBottomY(double liftY) {
        return liftY + CARRIAGE_TOP_Y_PX / PIXELS_PER_BLOCK;
    }

    /** Length of the hoist belt between the carriage and the mast cap, in blocks; never negative. */
    public static double hoistBeltLength(double liftY, int mastHeight) {
        return Math.max(0.0, mastTopY(mastHeight) - hoistBeltBottomY(liftY));
    }
}
