package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;

/**
 * Ponder scenes of the warehouse rail <b>network</b>: rails that meet at a corner, the machine turning on it, and the
 * racks the corner itself serves (M21, issue #1, ADR-033).
 * <p>
 * As in {@link CraneScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen. The order of the {@code .text(...)} calls defines the {@code text_1 … text_n} lang keys of each scene id, so
 * inserting a line renumbers every later key and {@code runData} and {@code de_de.json} must be updated together.
 * <p>
 * <b>Which faces the viewer sees</b> (derived in {@link TerminalScenes}, and the reason this scene is laid out the way
 * it is): Ponder's camera draws the <b>north</b> face of a block on the left half of the screen and the <b>west</b>
 * face on the right half, so north and west are <b>near</b> the viewer and south and east point away. The stage runs
 * aisle A east and aisle B south ({@link PonderNetwork#CORNER}), so both are seen along their length rather than
 * end-on, and every rack row stands on the <b>far</b> side of its own aisle — A's on its right (south), B's on its
 * left (east). A row on the near side would stand between the viewer and the machine, which is exactly what the first
 * cut of this scene did.
 * <p>
 * The one rack on the near side is the corner block's own <b>north</b> face, and that is the point: it is the rack the
 * viewer watches the arm reach into while the machine stands on the block both aisles share.
 */
public final class NetworkScenes {
    /** Duration of a normal caption, and the idle that lets it be read. */
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read (and to screenshot). */
    private static final int TRANSFER_TICKS = 40;
    private static final int CONTROL_TICKS = 40;
    /** Ticks between a click icon and what the click does, so the two are read as cause and effect. */
    private static final int CLICK_LEAD = 7;
    /** Amount the scene carries round the bend: enough to fill the grabber visibly. */
    private static final int CARRY_AMOUNT = 16;
    /** Amount the scene moves from one corner rack into the other. */
    private static final int CORNER_AMOUNT = 8;

    /** Position of the rack the trip starts at, on aisle A — on its far side, so it never hides the machine. */
    private static final int SOURCE_X = 3;
    private static final Side SOURCE_SIDE = Side.RIGHT;
    /** Position of the rack the trip ends at, on aisle B, one level up so the machine lifts while it turns. */
    private static final int TARGET_X = 3;
    private static final int TARGET_LEVEL = 1;
    /** Aisle B's far side. */
    private static final Side TARGET_SIDE = Side.LEFT;
    /**
     * The side both racks of the corner block lie on. It is the same word for two different faces of one block: on
     * aisle A, whose last position that block is, the left side is its north face; on aisle B, whose position 0 it is
     * at the same time, the left side is its east face. That coincidence is the whole ownership rule in one line.
     */
    private static final Side CORNER_SIDE = Side.LEFT;

    private NetworkScenes() {
    }

    /**
     * Rails around a corner: two aisles meeting at one block, the machine swinging a quarter turn on it, and the two
     * racks that block serves — one on each aisle, told apart by the way the interface faces.
     */
    public static void corner(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_corner", "Rails Around a Corner");

        PonderNetwork stage = PonderNetwork.corner(util);
        scene.configureBasePlate(0, 0, stage.plateSize());
        scene.scaleSceneView(0.8f);

        BlockPos dock = stage.dock();
        BlockPos corner = stage.corner();
        BlockPos cornerRackOnA = stage.rack(PonderNetwork.FIRST, PonderNetwork.RAILS_PER_AISLE, 0, CORNER_SIDE);
        BlockPos cornerRackOnB = stage.rack(PonderNetwork.SECOND, 0, 0, CORNER_SIDE);
        BlockPos source = stage.rack(PonderNetwork.FIRST, SOURCE_X, 0, SOURCE_SIDE);
        BlockPos target = stage.rack(PonderNetwork.SECOND, TARGET_X, TARGET_LEVEL, TARGET_SIDE);

        // Build the whole network while every position is still hidden, then fade the parts in one after another.
        stage.placeNetwork(scene, util);
        stage.placeStorage(scene, PonderNetwork.FIRST, SOURCE_X - 1, 0, SOURCE_SIDE);
        stage.placeStorage(scene, PonderNetwork.FIRST, SOURCE_X, 0, SOURCE_SIDE);
        stage.placeStorage(scene, PonderNetwork.SECOND, TARGET_X - 1, 0, TARGET_SIDE);
        stage.placeStorage(scene, PonderNetwork.SECOND, TARGET_X, 0, TARGET_SIDE);
        stage.placeStorage(scene, PonderNetwork.SECOND, TARGET_X, TARGET_LEVEL, TARGET_SIDE);
        // The two racks of the corner block itself, which are what the ownership rule is about.
        stage.placeStorage(scene, PonderNetwork.FIRST, PonderNetwork.RAILS_PER_AISLE, 0, CORNER_SIDE);
        stage.placeStorage(scene, PonderNetwork.SECOND, 0, 0, CORNER_SIDE);
        // The crane must not start moving before the scene says so.
        scene.world().setKineticSpeed(util.select().everywhere(), 0);

        // The creative motor sits in the base plate layer, so it fades in together with the plate.
        scene.showBasePlate();
        scene.idle(10);

        Selection firstAisle = stage.rails(util, PonderNetwork.FIRST);
        Selection secondAisle = stage.rails(util, PonderNetwork.SECOND);

        scene.world().showSection(util.select().position(stage.controller()), Direction.EAST);
        scene.world().showSection(util.select().position(dock), Direction.DOWN);
        // Without the corner block yet: it arrives with the rails that make it one (PonderNetwork#newRails).
        scene.world().showSection(stage.newRails(util, PonderNetwork.FIRST), Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.overlay().showText(TEXT_TICKS)
                .text("A warehouse is the Warehouse Rails in front of a Stacker Crane's dock")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(stage.aisle(PonderNetwork.FIRST, 2)));
        scene.idle(TEXT_IDLE);

        // --- rails that touch, connect -----------------------------------------------------------------------------
        // The shared block comes in here, with the run at right angles to the first one.
        scene.world().showSection(stage.newRails(util, PonderNetwork.SECOND), Direction.NORTH);
        scene.idle(FADE_IDLE + 5);
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("Rails connect wherever they touch, not only in a straight line")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(stage.aisle(PonderNetwork.SECOND, 2)));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.OUTPUT, "corner", util.select().position(corner), TEXT_TICKS + 20);
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("Two rails at right angles turn the block they share into a corner")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(corner));
        scene.idle(TEXT_IDLE);

        // --- each straight run is an aisle of its own --------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.BLUE, "aisle-a", firstAisle, TEXT_TICKS + 20);
        scene.overlay().showOutline(PonderPalette.GREEN, "aisle-b", secondAisle, TEXT_TICKS + 20);
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("Every straight run is one aisle: the one at the dock is A, the next one B")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(corner));
        scene.idle(TEXT_IDLE + 10);

        // The storage of both aisles. Fading in from the aisle side, so each row moves outwards into its own place.
        scene.world().showSection(util.select().fromTo(
                stage.inventory(PonderNetwork.FIRST, SOURCE_X - 1, 0, SOURCE_SIDE),
                stage.rack(PonderNetwork.FIRST, SOURCE_X, 0, SOURCE_SIDE)), Direction.SOUTH);
        scene.world().showSection(util.select().fromTo(
                stage.rack(PonderNetwork.SECOND, TARGET_X - 1, 0, TARGET_SIDE),
                stage.inventory(PonderNetwork.SECOND, TARGET_X, TARGET_LEVEL, TARGET_SIDE)), Direction.EAST);
        scene.idle(FADE_IDLE + 10);

        // --- the machine drives round the bend ---------------------------------------------------------------------
        // Power. Every kinetic block entity gets its speed at once, before any pose is scripted: setKineticSpeed
        // round-trips block entity NBT, which would overwrite a scripted pose.
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);
        scene.effects().rotationSpeedIndicator(dock);
        scene.idle(10);

        // The network is handed to the block entity with every pose: a Ponder level never discovers it (PonderNetwork).
        CraneScript crane = CraneScript.onNetwork(scene, dock, stage.network());

        scene.overlay().showText(TEXT_TICKS + 20)
                .text("The machine rolls onto that block, swings a quarter turn and drives on down the next aisle")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(corner));

        crane.moveTo(crane.at(PonderNetwork.FIRST, SOURCE_X, 0, SOURCE_SIDE), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(crane.extendedAt(PonderNetwork.FIRST, SOURCE_X, 0, SOURCE_SIDE), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.IRON_INGOT, CARRY_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(crane.at(PonderNetwork.FIRST, SOURCE_X, 0, SOURCE_SIDE), CranePhase.RETRACT_SOURCE);

        // Round the bend, lifting on the way: the target rack is one level up.
        crane.moveTo(crane.at(PonderNetwork.SECOND, TARGET_X, TARGET_LEVEL, TARGET_SIDE), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(crane.extendedAt(PonderNetwork.SECOND, TARGET_X, TARGET_LEVEL, TARGET_SIDE),
                CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(target);
        crane.moveTo(crane.at(PonderNetwork.SECOND, TARGET_X, TARGET_LEVEL, TARGET_SIDE), CranePhase.RETRACT_TARGET);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.INPUT, "from", util.select().position(source), TEXT_TICKS);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "to", util.select().position(target), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("So items reach every aisle of the warehouse, and one block can collect them all")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(target, Direction.NORTH));
        scene.idle(TEXT_IDLE);

        // --- the racks of the corner block --------------------------------------------------------------------------
        // The corner block's own two racks, held back until now so that they arrive as the point of this beat.
        scene.world().showSection(util.select()
                .fromTo(stage.inventory(PonderNetwork.FIRST, PonderNetwork.RAILS_PER_AISLE, 0, CORNER_SIDE),
                        cornerRackOnA)
                .add(util.select().fromTo(cornerRackOnB,
                        stage.inventory(PonderNetwork.SECOND, 0, 0, CORNER_SIDE))), Direction.DOWN);
        scene.idle(FADE_IDLE + 10);
        scene.overlay().showOutline(PonderPalette.BLUE, "rack-a", util.select().position(cornerRackOnA),
                TEXT_TICKS + 40);
        scene.overlay().showOutline(PonderPalette.GREEN, "rack-b", util.select().position(cornerRackOnB),
                TEXT_TICKS + 40);
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("A rack in the corner works too: it belongs to the aisle it faces away from")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(cornerRackOnA, Direction.NORTH));
        scene.idle(TEXT_IDLE + 10);

        // The machine serves both of them without leaving the corner block: one quarter turn between two racks.
        crane.moveTo(crane.at(PonderNetwork.SECOND, 0, 0, CORNER_SIDE), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(crane.extendedAt(PonderNetwork.SECOND, 0, 0, CORNER_SIDE), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.GOLD_INGOT, CORNER_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(crane.at(PonderNetwork.SECOND, 0, 0, CORNER_SIDE), CranePhase.RETRACT_SOURCE);

        scene.overlay().showText(TEXT_TICKS + 20)
                .text("It reaches both from the very same block, a quarter turn apart")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(corner));
        crane.moveTo(crane.at(PonderNetwork.FIRST, PonderNetwork.RAILS_PER_AISLE, 0, CORNER_SIDE),
                CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(crane.extendedAt(PonderNetwork.FIRST, PonderNetwork.RAILS_PER_AISLE, 0, CORNER_SIDE),
                CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(cornerRackOnA);
        crane.moveTo(crane.at(PonderNetwork.FIRST, PonderNetwork.RAILS_PER_AISLE, 0, CORNER_SIDE),
                CranePhase.RETRACT_TARGET);
        crane.moveTo(crane.at(PonderNetwork.FIRST, 0, 0, CORNER_SIDE), CranePhase.IDLE);
        scene.idle(10);

        // --- the address names the aisle ---------------------------------------------------------------------------
        // The addresses are asked of the same mapping the running game answers with, so this caption cannot drift from
        // what a player really reads through goggles. They are fixed for this stage, so de_de.json carries them too.
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("Its address says which: " + stage.address(PonderNetwork.FIRST,
                        PonderNetwork.RAILS_PER_AISLE, 0, CORNER_SIDE) + " on aisle A, "
                        + stage.address(PonderNetwork.SECOND, 0, 0, CORNER_SIDE) + " on aisle B")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(cornerRackOnA, Direction.NORTH));
        scene.idle(TEXT_IDLE);

        // --- the wrench closes a rail -------------------------------------------------------------------------------
        BlockPos closed = stage.aisle(PonderNetwork.SECOND, PonderNetwork.RAILS_PER_AISLE);
        scene.overlay().showControls(util.vector().topOf(closed), Pointing.DOWN, CONTROL_TICKS)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.idle(CLICK_LEAD);
        stage.closeRail(scene, PonderNetwork.SECOND);
        scene.overlay().showOutline(PonderPalette.RED, "closed", util.select().position(closed), TEXT_TICKS + 20);
        scene.overlay().showText(TEXT_TICKS + 40)
                .text("A Wrench closes a rail: it belongs to no warehouse, which keeps two of them apart and sends a "
                        + "stray rail away")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(closed))
                .colored(PonderPalette.RED);
        scene.idle(TEXT_IDLE + 20);

        scene.markAsFinished();
    }
}
