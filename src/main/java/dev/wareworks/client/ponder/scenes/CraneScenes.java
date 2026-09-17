package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.client.ponder.WareworksPonderPlugin;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;

/**
 * Ponder scenes of the stacker crane itself.
 * <p>
 * Storyboards run twice in different contexts: with a real {@code PonderLevel} when the UI opens, and with
 * <b>{@code level == null}</b> during lang datagen. Nothing here may touch the level, {@code Minecraft.getInstance()}
 * or {@code getHolderLookupProvider()} directly; that only ever happens inside instruction callbacks, which datagen
 * never runs.
 * <p>
 * The order of the {@code .text(...)} calls is the order of the {@code text_1 … text_n} lang keys. Inserting a line
 * renumbers every later key, so {@code runData} and {@code de_de.json} must be updated together.
 */
public final class CraneScenes {
    /** Duration of a normal caption, and the idle that lets it be read. */
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read (and to screenshot). */
    private static final int TRANSFER_TICKS = 40;

    private CraneScenes() {
    }

    /**
     * Overview: what a stacker crane is, the rails it runs on, the racks it serves, the controller behind it, the
     * rotational force that drives it, and the crane actually travelling, lifting and reaching into a rack.
     */
    public static void overview(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("stacker_crane_overview", "Moving Items with a Stacker Crane");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        BlockPos dock = aisle.dock(util);
        BlockPos controller = aisle.controller(util);
        BlockPos motor = aisle.motor(util);
        int firstRack = 4;
        int lastRack = 6;

        // Build the whole aisle while every position is still hidden, then fade the parts in one after another.
        aisle.placeAisle(scene, util);
        for (int position = firstRack; position <= lastRack; position++) {
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
            aisle.placeStorage(scene, util, position, 1, Side.LEFT);
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        }
        // The crane must not start moving before the scene says so.
        scene.world().setKineticSpeed(util.select().everywhere(), 0);

        // The creative motor sits in the base plate layer, so it fades in together with the plate.
        scene.showBasePlate();
        scene.idle(10);

        Selection rackLeft = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 2,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y + 1, aisle.aisleZ() - 1);
        Selection rackRight = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 1,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        BlockPos midRail = util.grid().at(aisle.dockX() + 3, PonderAisle.FLOOR_Y, aisle.aisleZ());
        BlockPos sampleRack = aisle.rack(util, firstRack, 0, Side.LEFT);

        scene.world().showSection(util.select().position(dock), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("Stacker Cranes store and retrieve items in a warehouse aisle")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(dock));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(aisle.rails(util), Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.overlay().showText(TEXT_TICKS)
                .text("The aisle is the straight line of Warehouse Rails in front of the dock")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(midRail));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(rackLeft, Direction.SOUTH);
        scene.world().showSection(rackRight, Direction.NORTH);
        scene.idle(FADE_IDLE + 10);
        scene.overlay().showText(TEXT_TICKS)
                .text("Storage sits on both sides of the aisle, on as many levels as the crane can reach")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(sampleRack, Direction.EAST));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(util.select().position(controller), Direction.EAST);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Warehouse Controller behind the dock keeps the stock and plans every job")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        scene.idle(TEXT_IDLE);

        // Power. Every kinetic block entity of the scene gets its speed at once, before any pose is scripted:
        // setKineticSpeed round-trips block entity NBT, which would overwrite a scripted pose.
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);
        scene.effects().rotationSpeedIndicator(dock);
        scene.overlay().showText(TEXT_TICKS)
                .sharedText(WareworksPonderPlugin.CRANE_NEEDS_ROTATION)
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(dock, Direction.DOWN));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Faster rotation moves it faster; without rotation it pauses where it is")
                .placeNearTarget()
                .pointAt(util.vector().topOf(motor));
        scene.idle(TEXT_IDLE);

        // The crane is animated through the client pose API; the block entity moves it with the shared motion.
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        scene.overlay().showText(TEXT_TICKS + 30)
                .text("It travels along the rails and lifts its carriage to the right level")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(midRail));
        crane.moveTo(CranePose.at(firstRack, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(CranePose.at(firstRack, 1, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        scene.idle(10);

        scene.overlay().showText(TEXT_TICKS)
                .text("Then its telescopic arm reaches into the rack and the grabber moves the items")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(aisle.rack(util, firstRack, 1, Side.LEFT), Direction.EAST));
        crane.moveTo(new CranePose(firstRack, 1, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.IRON_INGOT, 16);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(firstRack, 1, Side.LEFT), CranePhase.RETRACT_SOURCE);
        scene.idle(TEXT_IDLE - TRANSFER_TICKS);

        // The items must arrive somewhere: a crane never empties its grabber in mid-air, and the scene would teach the
        // opposite of the mod's promise. So they are delivered into a real location on the other side, like a job does.
        BlockPos delivery = aisle.rack(util, lastRack, 0, Side.RIGHT);
        crane.moveTo(CranePose.at(lastRack, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(lastRack, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(delivery);
        crane.moveTo(CranePose.at(lastRack, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        scene.overlay().showCenteredScrollInput(dock, Direction.UP, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The Mast Height on the dock sets how many levels the crane can reach")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(dock, Direction.UP));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }
}
