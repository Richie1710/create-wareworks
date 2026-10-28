package dev.wareworks.client.ponder.scenes;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.warehouse.LocationKind;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The Ponder scene of <b>several stacker cranes on one warehouse</b> (issue #14, ADR-075): a second dock without a
 * controller of its own that faces into the rails is crane 2 of the warehouse, both cranes work at once, a crane
 * reserves every rail up to its next stop before it moves and waits while another crane holds one of them, a crane
 * standing in the way moves aside, and on a comb one crane stands in a side aisle while the other drives past it.
 * <p>
 * The stage is {@link PonderNetwork#twoCranes}: the comb of the junction scene with its last block a second dock. A
 * comb rather than one straight aisle on purpose — on a straight aisle the two cranes can never pass each other, which is
 * the rule ("no crane ever cuts another off from its dock") and the reason to build side aisles, and a scene on one line
 * could not show the one thing a player has to see to believe it.
 * <p>
 * <b>Nothing here is invented.</b> Every beat is a state the traffic rules of ADR-075 really produce, worked through on
 * this very stage: the two first jobs go to the nearer crane each (crane 1 is three blocks and a turn from aisle B, crane
 * 2 five; the other way round for aisle C); crane 2's way home from aisle C to its home point never touches crane 1's
 * way back from aisle B; crane 1's way into aisle C runs over crane 2's home point, which is therefore in the way itself,
 * so crane 2 moves aside to its <b>dock</b> and not to its home point; and with crane 1 standing in aisle C, crane 2's
 * trip into aisle B and back leaves crane 1 a free way to its dock at every moment. The routes are the stage's own
 * ({@link CraneScript} drives each crane with the real motion and the real network), so every idle is as long as the
 * machine really takes.
 * <p>
 * As in every scene, the storyboard stays level-free: it also runs with {@code level == null} during lang datagen, and
 * the order of the {@code .text(...)} calls defines the {@code text_1 … text_n} lang keys, so a new line renumbers every
 * later key and {@code runData} and {@code de_de.json} must change together. A Ponder level draws no goggle tooltip, so
 * the goggle lines are told in a caption and not shown.
 */
public final class TwoCraneScenes {
    /** How long a caption stays on screen, and the longer idle after it, so captions never overlap (NetworkScenes). */
    private static final int SAY_TICKS = 85;
    private static final int SAY_IDLE = SAY_TICKS + 10;
    private static final int FADE_IDLE = 15;
    private static final int CONTROL_TICKS = 40;
    /** How long the grabber holds a full arm while it transfers; three trips and a wait make a long scene. */
    private static final int TRANSFER_TICKS = 28;
    /** The short pause at a drop before the grabber lets go, as in every crane scene. */
    private static final int RELEASE_LEAD = 10;
    private static final int AMOUNT = 16;

    /** The input beside crane 1's dock, on the far side of the main run. */
    private static final int INPUT_X = 0;
    /** The output beside crane 2's dock, which only crane 2 serves: the blocks beside a dock are its own crane's. */
    private static final int PORT_X = PonderNetwork.MAIN_RAILS;
    /** Crane 2's home point, on the main run between the two side aisles. */
    private static final int HOME_X = PonderNetwork.FIRST_JUNCTION + 2;
    /** The side of the main run every station of this stage stands on: its far side. */
    private static final Side RUN_SIDE = Side.RIGHT;
    /** The side of a southbound side aisle that faces away from the camera. */
    private static final Side TOOTH_SIDE = Side.LEFT;
    /** The rack crane 1 stores into in aisle C, one before that aisle's last. */
    private static final int EAST_NEAR_X = PonderNetwork.TOOTH_RAILS - 1;

    private TwoCraneScenes() {
    }

    /** One step of one crane in a beat where both move: starts the step and says how many ticks it takes. */
    @FunctionalInterface
    private interface Leg {
        int start();
    }

    /** "Two Cranes, One Warehouse". */
    public static void twoCranes(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_two_cranes", "Two Cranes, One Warehouse");

        PonderNetwork stage = PonderNetwork.twoCranes(util);
        scene.configureBasePlate(0, 0, stage.plateSize());
        scene.scaleSceneView(0.8f);

        BlockPos dock = stage.dock();
        BlockPos member = stage.memberDock();
        BlockPos input = stage.rack(PonderNetwork.FIRST, INPUT_X, 0, RUN_SIDE);
        BlockPos port = stage.rack(PonderNetwork.FIRST, PORT_X, 0, RUN_SIDE);
        BlockPos home = stage.rack(PonderNetwork.FIRST, HOME_X, 0, RUN_SIDE);
        BlockPos westRack = stage.rack(PonderNetwork.SECOND, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE);
        BlockPos eastRack = stage.rack(PonderNetwork.THIRD, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE);
        BlockPos eastNearRack = stage.rack(PonderNetwork.THIRD, EAST_NEAR_X, 0, TOOTH_SIDE);

        // Everything is built while its position is still hidden, then faded in one part after another.
        stage.placeNetwork(scene, util);
        stage.placeInput(scene, PonderNetwork.FIRST, INPUT_X, 0, RUN_SIDE);
        stage.placeOutput(scene, PonderNetwork.FIRST, PORT_X, 0, RUN_SIDE);
        stage.placeHomePoint(scene, PonderNetwork.FIRST, HOME_X, 0, RUN_SIDE);
        stage.placeStorage(scene, PonderNetwork.SECOND, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE);
        stage.placeStorage(scene, PonderNetwork.THIRD, EAST_NEAR_X, 0, TOOTH_SIDE);
        stage.placeStorage(scene, PonderNetwork.THIRD, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE);
        // Neither machine may start moving before the scene says so.
        scene.world().setKineticSpeed(util.select().everywhere(), 0);

        // Both creative motors sit in the base plate layer, so they fade in together with the plate.
        scene.showBasePlate();
        scene.idle(10);

        scene.world().showSection(util.select().position(stage.controller()), Direction.EAST);
        scene.world().showSection(util.select().position(dock), Direction.DOWN);
        scene.world().showSection(stage.rails(util, PonderNetwork.FIRST), Direction.DOWN);
        scene.world().showSection(stage.branchRails(util, PonderNetwork.SECOND), Direction.NORTH);
        scene.world().showSection(stage.branchRails(util, PonderNetwork.THIRD), Direction.NORTH);
        scene.idle(FADE_IDLE);
        scene.world().showSection(util.select().position(input).add(util.select().position(home)), Direction.NORTH);
        scene.world().showSection(util.select().fromTo(westRack,
                stage.inventory(PonderNetwork.SECOND, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE)), Direction.WEST);
        scene.world().showSection(util.select().fromTo(eastNearRack,
                stage.inventory(PonderNetwork.THIRD, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE)), Direction.WEST);
        scene.idle(FADE_IDLE);
        scene.overlay().showText(SAY_TICKS)
                .text("This warehouse is worked by one Stacker Crane: the one at its controller")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(dock));
        scene.idle(SAY_IDLE);

        // --- a second dock joins ----------------------------------------------------------------------------------
        scene.world().showSection(util.select().position(member), Direction.DOWN);
        scene.world().showSection(util.select().position(port), Direction.NORTH);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.GREEN, "second-dock", util.select().position(member), SAY_TICKS);
        scene.overlay().showText(SAY_TICKS)
                .text("A second dock facing into the same rails, without a controller of its own, becomes crane 2 of "
                        + "this warehouse")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(member));
        scene.idle(SAY_IDLE);

        scene.overlay().showOutline(PonderPalette.BLUE, "crane-1", util.select().position(dock), SAY_TICKS);
        scene.overlay().showOutline(PonderPalette.GREEN, "crane-2", util.select().position(member), SAY_TICKS);
        scene.overlay().showText(SAY_TICKS)
                .text("Both share the controller, the stock list and every terminal, and each job goes to the nearest "
                        + "free crane")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(stage.controller()));
        scene.idle(SAY_IDLE);

        // --- both at once ------------------------------------------------------------------------------------------
        // Power. Every kinetic block entity gets its speed at once, before any pose is scripted: setKineticSpeed
        // round-trips block entity NBT, which would overwrite a scripted pose.
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);
        scene.effects().rotationSpeedIndicator(dock);
        scene.effects().rotationSpeedIndicator(member);
        scene.idle(10);

        // Neither dock knows its rails in a Ponder level, and crane 2 does not know whose warehouse it works in: both
        // are handed over with every pose (CraneScript).
        CraneScript one = CraneScript.onNetwork(scene, dock, stage.network());
        CraneScript two = CraneScript.memberOnNetwork(scene, member, stage.network(), stage.memberFrame());

        scene.overlay().showText(SAY_TICKS)
                .text("Both work at the same time, each one nearer its own end")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(stage.aisle(PonderNetwork.FIRST, HOME_X)));
        // Crane 1 stores from the input into aisle B, crane 2 fetches from aisle C into its output.
        together(scene,
                carry(scene, one, PonderNetwork.FIRST, INPUT_X, RUN_SIDE, LocationKind.INPUT, PonderNetwork.SECOND,
                        PonderNetwork.TOOTH_RAILS, TOOTH_SIDE, LocationKind.STORAGE, westRack, Items.IRON_INGOT),
                carry(scene, two, PonderNetwork.THIRD, PonderNetwork.TOOTH_RAILS, TOOTH_SIDE, LocationKind.STORAGE,
                        PonderNetwork.FIRST, PORT_X, RUN_SIDE, LocationKind.OUTPUT, port, Items.GOLD_INGOT));

        // --- home points -------------------------------------------------------------------------------------------
        // Crane 1 goes back to its dock and crane 2 to the home point the warehouse paired with it; the two ways never
        // touch, so they drive at the same time.
        together(scene,
                List.of(() -> one.start(one.at(PonderNetwork.FIRST, 0, 0, RUN_SIDE), CranePhase.IDLE)),
                List.of(() -> two.start(two.at(PonderNetwork.FIRST, HOME_X, 0, RUN_SIDE), CranePhase.IDLE)));
        scene.overlay().showOutline(PonderPalette.OUTPUT, "home", util.select().position(home), SAY_TICKS);
        scene.overlay().showText(SAY_TICKS)
                .text("Warehouse Home Points say where each crane waits while it has nothing to do")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(home, Direction.NORTH));
        scene.idle(SAY_IDLE);

        // --- a reservation, and a crane that waits ------------------------------------------------------------------
        // Crane 1 picks up at the input; its next stop lies in aisle C, beyond crane 2.
        one.moveTo(one.extendedAt(PonderNetwork.FIRST, INPUT_X, 0, RUN_SIDE, LocationKind.INPUT),
                CranePhase.EXTEND_SOURCE);
        one.hold(Items.COPPER_INGOT, AMOUNT);
        one.dwell(CranePhase.PICK, TRANSFER_TICKS);
        one.moveTo(one.at(PonderNetwork.FIRST, INPUT_X, 0, RUN_SIDE), CranePhase.RETRACT_SOURCE);

        Selection way = util.select().fromTo(stage.aisle(PonderNetwork.FIRST, 1),
                        stage.aisle(PonderNetwork.FIRST, PonderNetwork.SECOND_JUNCTION))
                .add(util.select().fromTo(stage.aisle(PonderNetwork.THIRD, 1),
                        stage.aisle(PonderNetwork.THIRD, EAST_NEAR_X)));
        BlockPos held = stage.aisle(PonderNetwork.FIRST, HOME_X);
        scene.overlay().showOutline(PonderPalette.BLUE, "way", way, 2 * SAY_IDLE);
        scene.overlay().showText(SAY_TICKS)
                .text("Before it moves, a crane reserves every rail up to its next stop: all of them, or none")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(stage.aisle(PonderNetwork.FIRST, 2)));
        one.dwell(CranePhase.TRAVEL_TO_TARGET, SAY_IDLE);

        scene.overlay().showOutline(PonderPalette.RED, "held", util.select().position(held), SAY_TICKS);
        scene.overlay().showText(SAY_TICKS)
                .text("If another crane stands on one of those rails or has reserved it, the crane waits")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(held))
                .colored(PonderPalette.RED);
        one.dwell(CranePhase.TRAVEL_TO_TARGET, SAY_IDLE);

        scene.overlay().showControls(util.vector().topOf(dock), Pointing.DOWN, CONTROL_TICKS)
                .withItem(AllItems.GOGGLES.asStack());
        scene.idle(7);
        scene.overlay().showText(SAY_TICKS)
                .text("Goggles on a dock name its crane, what it does and which crane it is waiting for")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(dock));
        one.dwell(CranePhase.TRAVEL_TO_TARGET, SAY_IDLE);

        // --- moving aside --------------------------------------------------------------------------------------------
        // Crane 2's home point is on crane 1's way, so it is in the way itself: crane 2 goes back to its dock.
        scene.overlay().showText(SAY_TICKS)
                .text("A crane standing in the way moves aside: to its home point, or back to its own dock when that "
                        + "is in the way too")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(member));
        two.moveTo(two.at(PonderNetwork.FIRST, PORT_X, 0, RUN_SIDE), CranePhase.IDLE);
        one.moveTo(one.at(PonderNetwork.THIRD, EAST_NEAR_X, 0, TOOTH_SIDE), CranePhase.TRAVEL_TO_TARGET);
        one.moveTo(one.extendedAt(PonderNetwork.THIRD, EAST_NEAR_X, 0, TOOTH_SIDE), CranePhase.EXTEND_TARGET);
        one.dwell(CranePhase.DROP, RELEASE_LEAD);
        one.release();
        one.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(eastNearRack);
        one.moveTo(one.at(PonderNetwork.THIRD, EAST_NEAR_X, 0, TOOTH_SIDE), CranePhase.RETRACT_TARGET);
        one.show(CranePhase.IDLE);
        scene.idle(SAY_IDLE - SAY_TICKS);

        // --- passing on a comb --------------------------------------------------------------------------------------
        // Crane 1 stands in aisle C. Crane 2 drives past it along the main run into aisle B and back: crane 1's way to
        // its dock stays free the whole time, because a side aisle is off the run.
        scene.overlay().showOutline(PonderPalette.BLUE, "waits-aside", util.select().position(
                stage.aisle(PonderNetwork.THIRD, EAST_NEAR_X)), SAY_TICKS);
        scene.overlay().showText(SAY_TICKS)
                .text("On a comb, one crane can stand in a side aisle while the other drives past it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(stage.junction(PonderNetwork.THIRD)));
        two.moveTo(two.at(PonderNetwork.SECOND, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE),
                CranePhase.TRAVEL_TO_SOURCE);
        two.moveTo(two.extendedAt(PonderNetwork.SECOND, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE),
                CranePhase.EXTEND_SOURCE);
        two.hold(Items.IRON_INGOT, AMOUNT);
        two.dwell(CranePhase.PICK, TRANSFER_TICKS);
        two.moveTo(two.at(PonderNetwork.SECOND, PonderNetwork.TOOTH_RAILS, 0, TOOTH_SIDE),
                CranePhase.RETRACT_SOURCE);
        two.moveTo(two.at(PonderNetwork.FIRST, PORT_X, 0, RUN_SIDE), CranePhase.TRAVEL_TO_TARGET);
        // Crane 2 stands at its output, off crane 1's way: crane 1 drives home while crane 2 hands the iron over.
        List<Leg> handOver = new ArrayList<>();
        handOver.add(() -> two.start(two.extendedAt(PonderNetwork.FIRST, PORT_X, 0, RUN_SIDE, LocationKind.OUTPUT),
                CranePhase.EXTEND_TARGET));
        handOver.add(() -> two.startDwell(CranePhase.DROP, RELEASE_LEAD));
        handOver.add(() -> {
            two.release();
            return two.startDwell(CranePhase.DROP, TRANSFER_TICKS);
        });
        handOver.add(() -> {
            scene.effects().indicateSuccess(port);
            return two.start(two.at(PonderNetwork.FIRST, PORT_X, 0, RUN_SIDE), CranePhase.RETRACT_TARGET);
        });
        together(scene, List.of(() -> one.start(one.at(PonderNetwork.FIRST, 0, 0, RUN_SIDE), CranePhase.IDLE)),
                handOver);

        // --- the rule behind all of it ------------------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.BLUE, "end-1", util.select().position(dock), SAY_TICKS);
        scene.overlay().showOutline(PonderPalette.GREEN, "end-2", util.select().position(member), SAY_TICKS);
        scene.overlay().showText(SAY_TICKS)
                .text("No crane ever cuts another off from its dock, so on one straight run the two keep their order")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(stage.aisle(PonderNetwork.FIRST, HOME_X)));
        scene.idle(SAY_IDLE);

        scene.markAsFinished();
    }

    /**
     * One job of one crane as legs: drive to the source, take {@value #AMOUNT} of {@code item}, drive to the target and
     * hand them over, each arm stroke the one of its location kind (ADR-072).
     */
    private static List<Leg> carry(CreateSceneBuilder scene, CraneScript crane, int fromBranch, int fromX,
            Side fromSide, LocationKind fromKind, int toBranch, int toX, Side toSide, LocationKind toKind,
            BlockPos target, Item item) {
        List<Leg> legs = new ArrayList<>();
        legs.add(() -> crane.start(crane.at(fromBranch, fromX, 0, fromSide), CranePhase.TRAVEL_TO_SOURCE));
        legs.add(() -> crane.start(crane.extendedAt(fromBranch, fromX, 0, fromSide, fromKind),
                CranePhase.EXTEND_SOURCE));
        legs.add(() -> {
            crane.hold(item, AMOUNT);
            return crane.startDwell(CranePhase.PICK, TRANSFER_TICKS);
        });
        legs.add(() -> crane.start(crane.at(fromBranch, fromX, 0, fromSide), CranePhase.RETRACT_SOURCE));
        legs.add(() -> crane.start(crane.at(toBranch, toX, 0, toSide), CranePhase.TRAVEL_TO_TARGET));
        legs.add(() -> crane.start(crane.extendedAt(toBranch, toX, 0, toSide, toKind), CranePhase.EXTEND_TARGET));
        legs.add(() -> crane.startDwell(CranePhase.DROP, RELEASE_LEAD));
        legs.add(() -> {
            crane.release();
            return crane.startDwell(CranePhase.DROP, TRANSFER_TICKS);
        });
        legs.add(() -> {
            scene.effects().indicateSuccess(target);
            return crane.start(crane.at(toBranch, toX, 0, toSide), CranePhase.RETRACT_TARGET);
        });
        return legs;
    }

    /**
     * Runs two cranes' legs at the same time: each lane starts its next leg the moment its previous one has taken its
     * ticks, and the scene idles from one such moment to the next, so the storyboard stays a single timeline and its
     * total time is exactly as long as the longer lane.
     */
    private static void together(CreateSceneBuilder scene, List<Leg> first, List<Leg> second) {
        List<List<Leg>> lanes = List.of(first, second);
        int[] next = new int[lanes.size()];
        int[] index = new int[lanes.size()];
        int now = 0;
        while (true) {
            for (int lane = 0; lane < lanes.size(); lane++) {
                List<Leg> legs = lanes.get(lane);
                if (index[lane] < legs.size() && next[lane] == now)
                    next[lane] = now + Math.max(1, legs.get(index[lane]++).start());
            }
            int until = Integer.MAX_VALUE;
            int end = now;
            for (int lane = 0; lane < lanes.size(); lane++) {
                end = Math.max(end, next[lane]);
                if (index[lane] < lanes.get(lane).size())
                    until = Math.min(until, next[lane]);
            }
            if (until == Integer.MAX_VALUE) {
                if (end > now)
                    scene.idle(end - now);
                return;
            }
            scene.idle(until - now);
            now = until;
        }
    }
}
