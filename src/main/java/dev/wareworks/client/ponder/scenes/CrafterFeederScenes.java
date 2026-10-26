package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.station.WarehouseCrafterFeederBlock;
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
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Ponder scene of the Warehouse Crafter Feeder (issue #34, ADR-067): a production station whose patterns are laid into
 * a <b>vanilla Crafter</b> by the feeder on its back, one pulse per run, with the result going back into the warehouse
 * through a Warehouse Input in front of the Crafter's output.
 * <p>
 * <b>The build is the compact one the issue describes.</b> The station stands on the {@link Side#LEFT} rack plane, the
 * feeder on its back facing <i>along</i> the rack wall, the Crafter behind the neighbouring rack position with its
 * output facing the rack, and the Warehouse Input in that rack position. The left plane is chosen because its back row
 * is the one nearest Ponder's camera (north and west faces, see {@link ProductionScenes}), so the feeder and the Crafter
 * are in plain view without turning the scene round. The feeder's rotation comes from a creative motor set into the
 * base plate under it, as the dock's does, so nothing stands in front of the station.
 * <p>
 * <b>What the scene must not imply.</b> Wareworks crafts nothing here either: the crane carries, the feeder lays items
 * the crane already delivered into the Crafter's slots, and the <b>Crafter</b> crafts. That is ADR-024 and ADR-067 in one
 * sentence, and it is the closing line.
 * <p>
 * A {@code PonderLevel} runs no server, so neither the feeder's laying nor the Crafter's craft happens by itself: the
 * items going into the grid are shown as item icons over the Crafter, the pulse as Ponder's redstone indicator plus the
 * Crafter's own {@code crafting} state (its lit face), and the torches as items inserted into the input. Storyboards stay
 * level-free (they also run during lang datagen), and the order of the {@code .text(...)} calls defines the
 * {@code text_n} keys.
 */
public final class CrafterFeederScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    /** How long the crane holds a fully extended arm while it transfers. */
    private static final int TRANSFER_TICKS = 30;
    private static final int CONTROL_TICKS = 40;
    private static final int CLICK_LEAD = 7;
    /** One run as the feeder paces it at full speed: pulse, the Crafter's four-tick delay, and the rest of the run. */
    private static final int PULSE_TICKS = 4;
    private static final int RUN_TICKS = 20;
    /** The face of the feeder and the Crafter the camera reads: their north faces, the back of the rack wall. */
    private static final Direction FRONT = Direction.NORTH;

    /** The vanilla recipe the scene tells: one coal and one stick make four torches. */
    private static final int TORCHES_PER_RUN = 4;
    private static final int RUNS = 2;

    private CrafterFeederScenes() {
    }

    /** Crafting with a vanilla Crafter behind a production station (issue #34, ADR-067). */
    public static void feeder(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_crafter_feeder", "Crafting with a Vanilla Crafter");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Position 1 stays empty: the parked crane stands in front of it. The input comes next, then the station, so
        // the Crafter behind the input and the feeder behind the station sit side by side on the back row.
        int inputPosition = 2;
        int stationPosition = 3;
        int productPosition = 4;
        int stickPosition = 5;
        int coalPosition = 6;
        int firstRack = 4;
        int lastRack = 6;

        BlockPos dock = aisle.dock(util);
        BlockPos controller = aisle.controller(util);
        BlockPos input = aisle.rack(util, inputPosition, 0, Side.LEFT);
        BlockPos station = aisle.rack(util, stationPosition, 0, Side.LEFT);
        // Behind the station and behind the input: one block further from the aisle, on the station's back.
        BlockPos feeder = aisle.inventory(util, stationPosition, 0, Side.LEFT);
        BlockPos crafter = aisle.inventory(util, inputPosition, 0, Side.LEFT);
        BlockPos motor = feeder.below();
        BlockPos coal = aisle.rack(util, coalPosition, 0, Side.RIGHT);
        BlockPos sticks = aisle.rack(util, stickPosition, 0, Side.RIGHT);
        BlockPos product = aisle.rack(util, productPosition, 0, Side.RIGHT);
        // The feeder faces the Crafter, i.e. along the rack wall towards the input's column; its shaft is vertical
        // (a facing on the X axis with axis_along_first turns about Y), so the motor below drives it.
        Direction towardsCrafter = Direction.WEST;
        // The Crafter's output faces the rack, where the input stands: the aisle direction of the LEFT plane.
        Direction crafterFront = PonderAisle.outward(Side.LEFT).getOpposite();

        aisle.placeAisle(scene, util);
        aisle.placeInput(scene, util, inputPosition, 0, Side.LEFT);
        aisle.placeProduction(scene, util, stationPosition, 0, Side.LEFT);
        scene.world().setBlock(feeder, WareworksBlocks.WAREHOUSE_CRAFTER_FEEDER.getDefaultState()
                .setValue(WarehouseCrafterFeederBlock.FACING, towardsCrafter)
                .setValue(WarehouseCrafterFeederBlock.AXIS_ALONG_FIRST_COORDINATE, true), false);
        scene.world().setBlock(crafter, Blocks.CRAFTER.defaultBlockState()
                .setValue(BlockStateProperties.ORIENTATION, FrontAndTop.fromFrontAndTop(crafterFront, Direction.UP)),
                false);
        scene.world().setBlock(motor, AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP), false);
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 1,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        Selection stations = util.select().position(input).add(util.select().position(station));
        Selection feederSelection = util.select().position(feeder);
        Selection crafterSelection = util.select().position(crafter);

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(stations, Direction.DOWN);
        scene.idle(FADE_IDLE);

        scene.world().showSection(feederSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.GREEN, "station", util.select().position(station), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Crafter Feeder goes on the back of a Warehouse Production and needs rotation")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(feeder, FRONT));
        scene.idle(TEXT_IDLE);

        scene.world().showSection(crafterSelection, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "input", util.select().position(input), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A vanilla Crafter stands in front of it, with a Warehouse Input in front of the Crafter's output")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(crafter, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().topOf(station), Pointing.DOWN, CONTROL_TICKS).rightClick();
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("The station's patterns must be real crafting recipes, with exactly 1 item in each cell")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(station));
        scene.idle(TEXT_IDLE);

        // Stays up for both deliveries; the box hangs by the controller, clear of the aisle the crane drives through.
        scene.overlay().showOutline(PonderPalette.INPUT, "ingredients", util.select().position(coal)
                .add(util.select().position(sticks)), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS + 260)
                .text("Order torches, and the crane brings the coal and the sticks to the station")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        deliver(scene, crane, coalPosition, stationPosition, Items.COAL, RUNS);
        scene.effects().indicateSuccess(station);
        deliver(scene, crane, stickPosition, stationPosition, Items.STICK, RUNS);
        scene.effects().indicateSuccess(station);
        // Parks before the Crafter beats, so the mast never stands in front of the machine.
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        // The feeder lays the whole order into the empty grid, slot by slot as the pattern draws it, and then gives one
        // pulse per run. The items are icons over the Crafter, because nothing in a PonderLevel moves them.
        scene.overlay().showText(TEXT_TICKS + 2 * RUN_TICKS)
                .text("The feeder lays them into the Crafter as the pattern shows them, and pulses it once per run")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(crafter));
        scene.overlay().showControls(util.vector().topOf(crafter), Pointing.DOWN, CONTROL_TICKS)
                .withItem(new ItemStack(Items.COAL, RUNS));
        scene.idle(CONTROL_TICKS / 2);
        scene.overlay().showControls(util.vector().topOf(crafter), Pointing.DOWN, CONTROL_TICKS / 2)
                .withItem(new ItemStack(Items.STICK, RUNS));
        scene.idle(CONTROL_TICKS / 2 + 10);
        for (int run = 0; run < RUNS; run++) {
            scene.world().modifyBlock(crafter, state -> crafting(state, true), false);
            scene.effects().indicateRedstone(crafter);
            scene.idle(PULSE_TICKS);
            scene.world().createItemOnBeltLike(input, crafterFront.getOpposite(),
                    new ItemStack(Items.TORCH, TORCHES_PER_RUN));
            scene.idle(PULSE_TICKS);
            scene.world().modifyBlock(crafter, state -> crafting(state, false), false);
            scene.idle(RUN_TICKS - 2 * PULSE_TICKS);
        }
        scene.effects().indicateSuccess(input);
        scene.idle(TEXT_IDLE - 2 * RUN_TICKS);

        scene.overlay().showText(TEXT_TICKS + 160)
                .text("The torches go into the Warehouse Input, and the crane stores them like any other item")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        crane.moveTo(CranePose.at(inputPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.TORCH, TORCHES_PER_RUN * RUNS);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.LEFT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(productPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "stored", util.select().position(product), TEXT_TICKS);
        crane.moveTo(new CranePose(productPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(product);
        crane.moveTo(CranePose.at(productPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.RED, "refused", util.select().position(station), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A pattern that is no crafting recipe is shown red in the station and is never fetched")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(station));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS)
                .text("Wareworks still crafts nothing itself: the Crafter does")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(crafter, FRONT));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    /** One trip of the crane from a rack on the right plane to the station on the left one, holding {@code count}. */
    private static void deliver(CreateSceneBuilder scene, CraneScript crane, int from, int to,
            Item item, int count) {
        crane.moveTo(CranePose.at(from, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(from, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(item, count);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(from, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(to, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(to, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(to, 0, Side.LEFT), CranePhase.RETRACT_TARGET);
    }

    /** The Crafter with its {@code crafting} face lit or dark; {@code triggered} follows it, as the pulse does. */
    private static BlockState crafting(BlockState state, boolean on) {
        return state.setValue(CrafterBlock.CRAFTING, on).setValue(BlockStateProperties.TRIGGERED, on);
    }
}
