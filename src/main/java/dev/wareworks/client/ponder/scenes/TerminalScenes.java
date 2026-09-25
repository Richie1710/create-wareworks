package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.content.station.WarehouseTerminalBlock;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.registry.WareworksBlocks;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;

/**
 * Ponder scenes of the warehouse terminal: placing it (where the screen and the intake port end up) and requesting
 * items at it.
 * <p>
 * As in {@link WarehouseScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen. The order of the {@code .text(...)} calls defines the {@code text_1 … text_n} lang keys of each scene id.
 * <p>
 * <b>Which faces the viewer sees.</b> Ponder's camera starts at {@code xRotation = -35}, {@code yRotation = 145}
 * ({@code PonderScene.SceneTransform}), which draws the <b>north</b> face of every block on the left half of the
 * screen and the <b>west</b> face on the right half; south and east point away from the viewer. On this stage the
 * aisle runs east, so a station on the {@link Side#RIGHT} rack plane shows both its intake port (north, towards the
 * aisle) and its west face. The terminal's screen is therefore put on {@link #SCREEN} in both scenes — the only one of
 * its three non-port faces the viewer can read.
 * <p>
 * <b>The screen itself is never opened.</b> {@code WarehouseTerminalBlockEntity#openScreen} needs a {@code ServerPlayer}
 * and a {@code PonderLevel} is client-side, so Ponder has no way to show a real menu (Create's own stock ticker scenes
 * have the same limitation). Both scenes therefore <i>represent</i> the screen with {@code showControls} icons on the
 * display face plus text; the wording of the click rules follows {@code wareworks.gui.terminal.amount_hint}, so the
 * scene and the screen say the same thing.
 */
public final class TerminalScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read (and to screenshot). */
    private static final int TRANSFER_TICKS = 40;
    private static final int CONTROL_TICKS = 40;
    /** Half a turn of the scene, which swaps the two faces the camera draws for the two it hides. */
    private static final float HALF_TURN = 180;
    /**
     * How long a {@link #HALF_TURN} needs to settle: {@code RotateSceneInstruction} chases the new angle with a factor
     * of 0.1 per tick, so about forty ticks leave less than two degrees of the turn.
     */
    private static final int TURN_IDLE = 40;
    /** Ticks between a control icon appearing and the change it stands for, everywhere in these scenes. */
    private static final int CLICK_LEAD = 7;
    /** Amount the requesting scene asks for: a full stack, so the grabber is visibly loaded. */
    private static final int REQUEST_AMOUNT = 64;
    /** The face the viewer can read on a station of the {@link Side#RIGHT} rack plane (see the class comment). */
    private static final Direction SCREEN = Direction.WEST;
    /** The screen face as the block state stores it: one quarter counter-clockwise from a northward intake port. */
    private static final TerminalDisplaySide SCREEN_SIDE = TerminalDisplaySide.LEFT;

    private TerminalScenes() {
    }

    // --- placing -----------------------------------------------------------------------------------------------------

    /** Where the screen and the intake port of a terminal end up, and how the wrench turns the screen (ADR-022). */
    public static void placing(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_terminal", "Placing a Warehouse Terminal");

        PonderAisle aisle = PonderAisle.SMALL;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.95f);

        int terminalPosition = 3;
        int firstRack = 2;
        int lastRack = 3;
        // The face a station on the right-hand rack plane must reach through: the aisle lies opposite its outward side.
        Direction intake = PonderAisle.outward(Side.RIGHT).getOpposite();
        BlockPos terminal = aisle.rack(util, terminalPosition, 0, Side.RIGHT);

        aisle.placeAisle(scene, util);
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
        // Placed by a player standing west of the rack and looking east: the screen faces them and the intake port
        // starts on the face opposite it, which is not the aisle. That misalignment is the story of this scene.
        aisle.placeTerminal(scene, util, terminalPosition, 0, Side.RIGHT, SCREEN.getOpposite(),
                TerminalDisplaySide.BACK);
        // The crane only frames the aisle here; nothing in this scene moves.
        scene.world().setKineticSpeed(util.select().everywhere(), 0);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 2,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 1);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);

        // Fading east means it moves east into place, i.e. it comes in from the west, where the player stands.
        scene.world().showSection(util.select().position(terminal), Direction.EAST);
        scene.idle(FADE_IDLE);
        scene.overlay().showControls(util.vector().blockSurface(terminal, SCREEN), Pointing.LEFT, CONTROL_TICKS)
                .rightClick()
                .withItem(WareworksBlocks.WAREHOUSE_TERMINAL.asStack());
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Warehouse Terminal is placed with its screen facing you")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, SCREEN));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.INPUT, "port", util.select().position(terminal), TEXT_TICKS);
        scene.overlay().showLine(PonderPalette.INPUT, util.vector().centerOf(terminal),
                util.vector().centerOf(terminal.relative(intake)), TEXT_TICKS);
        // The precondition is real: WarehouseTerminalBlockEntity#alignToAisle leaves the port alone when the screen
        // already occupies the aisle face, which is what a player standing in the aisle produces. text_4 names the
        // wrench as the way out of that state, so neither beat pretends the correction is unconditional.
        scene.overlay().showText(TEXT_TICKS)
                .text("Placed from beside the rack, the controller turns its arm port onto the aisle by itself")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(terminal));
        scene.idle(30);
        // Only the port moves; the screen keeps the world face it was placed on, which is one quarter
        // counter-clockwise from the new port.
        scene.world().modifyBlock(terminal, state -> state.setValue(WarehouseTerminalBlock.FACING, intake)
                .setValue(WarehouseTerminalBlock.DISPLAY, SCREEN_SIDE), false);
        scene.effects().indicateSuccess(terminal);
        scene.idle(TEXT_IDLE - 30);

        scene.overlay().showText(TEXT_TICKS + 20)
                .text("Stand where you want to read it: the screen keeps its face, only the port moves")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, SCREEN));
        scene.idle(TEXT_IDLE + 10);

        // Three clicks walk the screen around the three faces that are not the port and back to the readable one.
        // TerminalDisplaySide#clockwise() walks the constants in declaration order, which is exactly what
        // BlockState#cycle does, so cycleBlockProperty stays in step with WarehouseTerminalBlock#getRotatedBlockState.
        int clicks = TerminalDisplaySide.values().length;
        int clickDwell = 45;
        int lastClickDwell = 15;
        // Counted rather than guessed, so the text below covers the beat exactly and never runs into the next one.
        int wrenchBeat = 2 * TURN_IDLE + (clicks - 1) * (CLICK_LEAD + clickDwell) + CLICK_LEAD + lastClickDwell;
        scene.overlay().showText(wrenchBeat)
                .text("A Wrench turns the screen to the next face, never onto the port, and off the aisle side")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(terminal));
        // The camera draws only the north and west faces, and north is the port, so the single readable screen face is
        // west: from the default view every wrench click would move the screen out of sight and the terminal would
        // simply go blank. Turning the scene by half a turn puts east and south in front for the clicks and brings the
        // screen back into view when it turns back, so all three faces the screen visits are seen once.
        scene.rotateCameraY(HALF_TURN);
        scene.idle(TURN_IDLE);
        for (int click = 1; click <= clicks; click++) {
            scene.overlay().showControls(util.vector().blockSurface(terminal, Direction.UP), Pointing.DOWN, 30)
                    .rightClick()
                    .withItem(AllItems.WRENCH.asStack());
            scene.idle(CLICK_LEAD);
            scene.world().cycleBlockProperty(terminal, WarehouseTerminalBlock.DISPLAY);
            scene.idle(click == clicks ? lastClickDwell : clickDwell);
        }
        // The last click has put the screen back on the west face; turning back reveals it there again.
        scene.rotateCameraY(-HALF_TURN);
        scene.effects().indicateSuccess(terminal);
        scene.idle(TURN_IDLE);

        scene.overlay().showControls(util.vector().blockSurface(terminal, SCREEN), Pointing.LEFT, CONTROL_TICKS)
                .withItem(AllItems.GOGGLES.asStack());
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("Goggles show its address, what was delivered here and why a request was refused")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, SCREEN));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    // --- requesting --------------------------------------------------------------------------------------------------

    /** Asking for items at the screen: the crane fetches them and delivers into the terminal's own slots. */
    public static void requesting(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_requesting", "Requesting Items at a Terminal");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        int terminalPosition = 2;
        int firstRack = 4;
        int lastRack = 6;
        int sourcePosition = 5;

        BlockPos dock = aisle.dock(util);
        BlockPos terminal = aisle.rack(util, terminalPosition, 0, Side.RIGHT);
        BlockPos source = aisle.rack(util, sourcePosition, 0, Side.LEFT);

        aisle.placeAisle(scene, util);
        aisle.placeTerminal(scene, util, terminalPosition, 0, Side.RIGHT, SCREEN_SIDE);
        // Extracting: the closing beat is about emptying the terminal, and only a funnel attached to it and in extract
        // mode does that. An inserting funnel would be the one block state that cannot do what the text says.
        aisle.placeExtractingFunnelAbove(scene, terminal);
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ()), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y,
                aisle.aisleZ() - 2, aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 1),
                Direction.DOWN);
        scene.idle(FADE_IDLE + 5);

        scene.world().showSection(util.select().fromTo(terminal.getX(), terminal.getY(), terminal.getZ(),
                terminal.getX(), terminal.getY() + 1, terminal.getZ()), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showControls(util.vector().blockSurface(terminal, SCREEN), Pointing.LEFT, CONTROL_TICKS)
                .rightClick();
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("Right-click the screen with an empty hand to open the terminal")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, SCREEN));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("It lists everything the aisle holds, and the search box finds an item by name")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, SCREEN));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().blockSurface(terminal, SCREEN), Pointing.LEFT, 30)
                .leftClick();
        scene.idle(10);
        scene.overlay().showControls(util.vector().blockSurface(terminal, SCREEN), Pointing.LEFT, 30)
                .leftClick()
                .whileSneaking();
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("Click an item for the amount in the field, Shift for a stack, Ctrl for everything")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, SCREEN));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().blockSurface(terminal, SCREEN), Pointing.LEFT, 30)
                .leftClick();
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("Clicking the same item again grows the open request instead of starting a second trip")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(terminal));
        scene.idle(TEXT_IDLE);

        CraneScript crane = CraneScript.parkedAt(scene, dock);
        scene.overlay().showOutline(PonderPalette.INPUT, "source", util.select().position(source), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The crane fetches the items from the locations that hold them")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(source, Direction.EAST));
        crane.moveTo(CranePose.at(sourcePosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(sourcePosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.IRON_INGOT, REQUEST_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(sourcePosition, 0, Side.LEFT), CranePhase.RETRACT_SOURCE);
        scene.idle(10);

        scene.overlay().showText(TEXT_TICKS)
                .text("and drops them into the terminal's own slots")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, SCREEN));
        crane.moveTo(CranePose.at(terminalPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(terminalPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(terminal);
        crane.moveTo(CranePose.at(terminalPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        // The flap is only the funnel sound: a vertical funnel has no flap geometry (see
        // PonderAisle#placeInsertingFunnelAbove), so without the outline and the particles below this beat would be a
        // still picture with a text box on it — the crane has parked and nothing else is left to move.
        scene.world().flapFunnel(terminal.above(), true);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "pickup", util.select().position(terminal)
                .add(util.select().position(terminal.above())), TEXT_TICKS);
        scene.effects().indicateSuccess(terminal.above());
        scene.overlay().showText(TEXT_TICKS)
                .text("Funnels, chutes, hoppers and Mechanical Arms can pull them out from there")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(terminal.above()));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }
}
