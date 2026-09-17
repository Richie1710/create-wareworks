package dev.wareworks.client.render;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.wareworks.Wareworks;

/**
 * Partial models of the animated stacker crane ({@code docs/stacker-crane.md} §7), client only.
 * <p>
 * Model files: {@code assets/wareworks/models/block/stacker_crane/<name>.json}, hand-made Blockbench JSON with Create
 * textures. They are authored like the dock block model: the aisle direction points <b>north</b> (-Z), the crane's right
 * rack side ({@code Side.RIGHT}, the clockwise side) is east (+X), and the crane stands in the block of its aisle position
 * at dock level. {@link StackerCraneRenderer} places and animates them; the dimensions it relies on are in
 * {@link CraneModelLayout}.
 * <p>
 * The fields are strong static references on purpose: Flywheel's partial model registry holds weak values, and partials
 * must exist before model baking. {@link #init()} is therefore called from the {@code WareworksClient} constructor
 * (ADR-013).
 */
public final class WareworksPartialModels {
    /** Chassis on the rail (andesite casing, brass buffers). */
    public static final PartialModel CRANE_BASE = block("base");
    /** One wheel, centred on its axle at the block centre; drawn twice and turned with the travelled distance. */
    public static final PartialModel CRANE_WHEEL = block("wheel");
    /** One block of the mast (industrial iron rails, dark web, rack); stacked mast height times. */
    public static final PartialModel CRANE_MAST_SEGMENT = block("mast_segment");
    /** Mast cap with the hoist drum, on top of the last segment. */
    public static final PartialModel CRANE_MAST_TOP = block("mast_top");
    /** One block of the hoist belt between the carriage and the mast cap. */
    public static final PartialModel CRANE_HOIST_BELT = block("hoist_belt");
    /** Lift carriage: brass guide frame around the mast and the deck carrying the telescopic arm. */
    public static final PartialModel CRANE_CARRIAGE = block("carriage");
    /** First telescopic stage; authored for an extension towards +X, moves half the extension. */
    public static final PartialModel CRANE_ARM_OUTER = block("arm_outer");
    /** Second telescopic stage; authored for an extension towards +X, moves the full extension. */
    public static final PartialModel CRANE_ARM_INNER = block("arm_inner");
    /** Brass handling head at the tip of the inner stage; moves with it. */
    public static final PartialModel CRANE_GRABBER = block("grabber");

    private WareworksPartialModels() {
    }

    private static PartialModel block(String name) {
        return PartialModel.of(Wareworks.asResource("block/stacker_crane/" + name));
    }

    /** Loads this class, so that every partial is created before the first model bake. */
    public static void init() {
        // static initialisation only
    }
}
