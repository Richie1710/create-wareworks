package dev.wareworks.client.render;

/**
 * Where a fluid bay's vessel stands and where the fluid inside it is drawn, in model pixels of a bay authored facing
 * north ({@code docs/warehouse-system.md} §3.9, M30 step 5, issue #21).
 *
 * <p>
 * <b>Why this is a class of its own.</b> Half of a fluid bay's look is a model file and half of it is a box computed
 * every frame by {@link FluidBayRenderer}. The model file is swept by {@code CraneModelLayoutTest}; the box is the one
 * geometry no model file can express, because the fluid's height is the readout. So the box's corners live here as
 * constants and one function, and the same test reads them back and holds them to the vessel the model really has.
 * That is the arrangement {@link CraneModelLayout} already uses for the crane's arm, for the same reason.
 *
 * <p>
 * <b>The vessel is the rack bay's load, in a tank.</b> It occupies exactly the window a rack bay's goods occupy — x
 * and z from {@value #VESSEL_MIN_X_PX} to {@value #VESSEL_MAX_X_PX}, standing on the plane a rack bay's pallet stands
 * on — so a wall that mixes tanks and racks keeps one rhythm, which is the whole of issue #20's fourth reason carried
 * over to #21. Its floor is {@value #WALL_PX} px thick, like its walls, so a tank is a vessel and not a trough with a
 * hole in it.
 *
 * <p>
 * <b>Its rim is the arm port's sill</b> ({@value #VESSEL_RIM_PX} px, {@code CraneModelLayoutTest.PORT_WINDOW_MIN_Y_PX}),
 * and that is the bound the whole design is cut to rather than a number somebody liked. The crane's arm sweeps the
 * block from end to end at every depth once it is extended, through the 8 x 4 px port every warehouse member carries
 * on its aisle side; its lowest point is the arm floor at 9.5 px. A tank whose rim or whose fluid reached into that
 * window would be a grabber visibly travelling through lava on its way to a container — so the tank stops at the
 * sill, the fluid stops with it, and the window a player reads the level through is the <b>same</b> 8 px that the arm
 * reaches through, one storey lower. Five pixels of fluid is what is left, and it is the whole budget.
 *
 * <p>
 * <b>The fluid box is inset from the walls</b> by {@value #HULL_GAP_PX} px, which is Create's own tank hull gap
 * ({@code FluidTankRenderer}: {@code 1/16 + 1/128}). A fluid quad coplanar with a solid wall quad z-fights, and a
 * tank that flickers along its seams at certain angles is the kind of fault that is reported as "the texture is
 * broken" rather than as a renderer bug. The front is inset as well, so the surface sits <i>behind</i> the opening
 * and the vessel reads as a vessel rather than as a sticker on the block's face.
 *
 * <p>
 * <b>A bay that holds anything at all shows at least {@value #MIN_FILM_PX} px</b>, because "is there anything in this
 * tank" is the question a player asks from the aisle and 1 mB of a brass bay is 1/256 000 of five pixels. It is the
 * same rounding {@code BayColumn#fillStep} already does for items, and a far milder one: a single item makes a rack
 * bay show a quarter of its load, where a single millibucket here shows a tenth of the tank's height.
 */
public final class FluidBayLayout {
    /** The vessel's footprint, across the bay and into the rack: the rack bay's load window, byte for byte. */
    public static final float VESSEL_MIN_X_PX = 3.0F;
    public static final float VESSEL_MAX_X_PX = 13.0F;
    public static final float VESSEL_MIN_Z_PX = 3.0F;
    public static final float VESSEL_MAX_Z_PX = 13.0F;
    /** Where the vessel stands: the top of the load beam, the plane a rack bay's pallet stands on. */
    public static final float VESSEL_FLOOR_PX = 3.0F;
    /** The vessel's rim, which is the sill of the arm port above it (see the class comment). */
    public static final float VESSEL_RIM_PX = 9.0F;
    /** How thick the floor and the three walls are. */
    public static final float WALL_PX = 1.0F;

    /** The gap between the fluid and the vessel it stands in; Create's own tank hull gap. */
    public static final float HULL_GAP_PX = 0.125F;
    /** Where the fluid stands: on the vessel's floor. */
    public static final float FLUID_FLOOR_PX = VESSEL_FLOOR_PX + WALL_PX;
    /** How much of the vessel's height the fluid may fill — the whole of it, less its floor. */
    public static final float FLUID_DEPTH_PX = VESSEL_RIM_PX - FLUID_FLOOR_PX;
    public static final float FLUID_MIN_X_PX = VESSEL_MIN_X_PX + WALL_PX + HULL_GAP_PX;
    public static final float FLUID_MAX_X_PX = VESSEL_MAX_X_PX - WALL_PX - HULL_GAP_PX;
    /** The back is behind the back wall; the front stops short of the opening rather than at it. */
    public static final float FLUID_MIN_Z_PX = VESSEL_MIN_Z_PX + WALL_PX + HULL_GAP_PX;
    public static final float FLUID_MAX_Z_PX = VESSEL_MAX_Z_PX - HULL_GAP_PX;
    /** The thinnest film a bay that holds anything at all draws (see the class comment). */
    public static final float MIN_FILM_PX = 0.5F;

    private FluidBayLayout() {
    }

    /**
     * The y of the fluid's surface for a bay holding {@code millibuckets} of {@code capacity}, in model pixels.
     * <p>
     * {@link #FLUID_FLOOR_PX} for an empty bay (and then nothing is drawn at all), never below
     * {@code FLUID_FLOOR_PX + }{@value #MIN_FILM_PX} for one that holds anything, and never above the rim — a bay over
     * a lowered configured capacity keeps every millibucket it has ({@code BayContents}), so the share really can
     * exceed one and must be clamped here rather than overflow the vessel.
     *
     * @param millibuckets what the bay holds; at or below 0 the bay is empty
     * @param capacity     what it holds when full; at or below 0 counts as full, as {@code BayColumn#fillStep} does
     */
    public static float surfacePx(long millibuckets, long capacity) {
        if (millibuckets <= 0)
            return FLUID_FLOOR_PX;
        if (capacity <= 0)
            return FLUID_FLOOR_PX + FLUID_DEPTH_PX;
        double share = Math.min(1.0, (double) millibuckets / (double) capacity);
        float height = Math.min(FLUID_DEPTH_PX, Math.max(MIN_FILM_PX, (float) (share * FLUID_DEPTH_PX)));
        return FLUID_FLOOR_PX + height;
    }

    /** That surface measured from the vessel's floor: 0 for an empty bay, {@link #FLUID_DEPTH_PX} for a full one. */
    public static float filmPx(long millibuckets, long capacity) {
        return surfacePx(millibuckets, capacity) - FLUID_FLOOR_PX;
    }
}
