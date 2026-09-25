package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.station.TerminalDisplaySide;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.registry.WareworksArmInteractionPoints;
import net.createmod.catnip.math.Pointing;
import net.createmod.catnip.nbt.NBTHelper;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Ponder scene of the warehouse production station: the warehouse brings the ingredients of a pattern to the station,
 * the player's own machine turns them into something else, and the product comes back through an ordinary warehouse
 * input.
 * <p>
 * The scene exists to make <b>ADR-024</b> unmistakable: <i>Wareworks delivers and collects; it never crafts.</i> The
 * only block that changes items here is a Create Mechanical Crafter the scene builds <i>next to</i> the station, driven
 * by its own shaft and fed by the player's own Mechanical Arm, exactly as a player would build it.
 * <p>
 * <b>Why an arm and not a funnel.</b> A funnel cannot move items from a station into a machine beside it: in extract
 * mode {@code FunnelBlockEntity#activateExtractor} only ever drops an item entity in front of its mouth and has no
 * path that inserts into a block, and a Mechanical Crafter never picks item entities up. An arm does exactly what
 * the beat needs, through the same capability a funnel would use, and both of its targets are the interaction points
 * M12 registered ({@link WareworksArmInteractionPoints}). Its points are written straight into the block entity's
 * {@code InteractionPoints} list, because a storyboard has no level to select them with.
 * <p>
 * As in {@link WarehouseScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen. The order of the {@code .text(...)} calls defines the {@code text_1 … text_n} lang keys of the scene id.
 * <p>
 * <b>Which faces the viewer sees</b> (derived in {@link TerminalScenes}): Ponder's camera draws the <b>north</b> face of
 * a block on the left half and the <b>west</b> face on the right half, so a station on the {@link Side#RIGHT} rack plane
 * shows its intake port (north, towards the aisle) and its west face. {@link #FRONT} is that readable face; the
 * storage locations on the {@link Side#LEFT} plane are read from {@code PonderAisle.outward(LEFT)}, their north side.
 * <p>
 * <b>No screen is ever opened.</b> {@code WarehouseProductionBlockEntity#openScreen} needs a {@code ServerPlayer} and a
 * {@code PonderLevel} is client-side, so the pattern editor and the terminal are <i>represented</i> with
 * {@code showControls} icons plus text, the same way {@link TerminalScenes} does it.
 * <p>
 * The crane always stands in the aisle <i>in front of</i> the rack plane it serves, so while it drives it covers part
 * of the row behind it. That is unavoidable and the same in the shipped scenes; the arm and machine beats therefore
 * start only after the crane has parked again.
 */
public final class ProductionScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    private static final int FADE_IDLE = 15;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read (and to screenshot). */
    private static final int TRANSFER_TICKS = 40;
    private static final int CONTROL_TICKS = 40;
    /** How long one arm movement needs: {@code ArmBlockEntity} advances by {@code speed / 1024} per tick. */
    private static final int ARM_MOVE_TICKS = 34;
    /** The face the viewer can read on a station of the {@link Side#RIGHT} rack plane (see the class comment). */
    private static final Direction FRONT = Direction.WEST;
    /**
     * The face of the player's machine the arm reaches through: its aisle side, the one the camera draws.
     * {@code AllArmInteractionPointTypes.CrafterPoint} reaches for the face <i>opposite</i> a crafter's
     * {@code HORIZONTAL_FACING}, so the crafter is turned away from the aisle to be fed from it.
     */
    private static final Direction MACHINE_INTAKE = PonderAisle.outward(Side.RIGHT).getOpposite();
    /** Create's arm interaction point type of a Mechanical Crafter; the station's own type comes from the registry. */
    private static final ResourceLocation CRAFTER_POINT = Create.asResource("crafter");
    /** The terminal's screen face as the block state stores it: one quarter counter-clockwise from a northward port. */
    private static final TerminalDisplaySide SCREEN_SIDE = TerminalDisplaySide.LEFT;
    /** The ingredients of the pattern the scene tells, and the product one run of the machine makes from them. */
    private static final int INGREDIENT_AMOUNT = 3;
    private static final int PRODUCT_AMOUNT = 12;

    private ProductionScenes() {
    }

    /**
     * One saved arm interaction point, in the shape {@code ArmInteractionPoint#serialize} writes and
     * {@code ArmBlockEntity#initInteractionPoints} reads back: the registered type id, the target relative to the arm,
     * and the mode. Level-free, so a storyboard may build it.
     */
    private static CompoundTag armPoint(ResourceLocation type, BlockPos arm, BlockPos target, Mode mode) {
        CompoundTag point = new CompoundTag();
        point.putString("Type", type.toString());
        point.put("Pos", NbtUtils.writeBlockPos(target.subtract(arm)));
        NBTHelper.writeEnum(point, "Mode", mode);
        return point;
    }

    /** What a production station is for: the warehouse feeds a machine and stores what comes back (ADR-024). */
    public static void production(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_production", "Feeding Machines from a Warehouse");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Position 1 is left empty on this plane: the parked crane stands in front of it and would hide it.
        int terminalPosition = 2;
        int inputPosition = 3;
        int stationPosition = 4;
        // The player's own machinery continues the row past the station, where the camera sees it from the aisle side:
        // the arm next to the station, the machine next to the arm. Starting one position later pushed the machine
        // onto the last column of the plate, where it was read against the edge.
        int armPosition = 5;
        int machinePosition = 6;
        int firstRack = 4;
        int lastRack = 6;
        // The far storage location holds the ingredients, the near one takes the product: two trips in opposite
        // directions, so the crane is never merely repeating itself.
        int ingredientPosition = 6;
        int productPosition = 4;

        BlockPos dock = aisle.dock(util);
        BlockPos controller = aisle.controller(util);
        BlockPos terminal = aisle.rack(util, terminalPosition, 0, Side.RIGHT);
        BlockPos input = aisle.rack(util, inputPosition, 0, Side.RIGHT);
        BlockPos station = aisle.rack(util, stationPosition, 0, Side.RIGHT);
        BlockPos arm = aisle.rack(util, armPosition, 0, Side.RIGHT);
        BlockPos machine = aisle.rack(util, machinePosition, 0, Side.RIGHT);
        // A Mechanical Crafter turns on the axis of its facing, so the shaft goes behind it, away from both the aisle
        // and the face the arm reaches for.
        BlockPos shaft = machine.relative(MACHINE_INTAKE.getOpposite());
        BlockPos ingredients = aisle.rack(util, ingredientPosition, 0, Side.LEFT);
        BlockPos product = aisle.rack(util, productPosition, 0, Side.LEFT);

        aisle.placeAisle(scene, util);
        aisle.placeTerminal(scene, util, terminalPosition, 0, Side.RIGHT, SCREEN_SIDE);
        aisle.placeInput(scene, util, inputPosition, 0, Side.RIGHT);
        aisle.placeInsertingFunnelAbove(scene, input);
        aisle.placeProduction(scene, util, stationPosition, 0, Side.RIGHT);
        // The player's machine: a Mechanical Crafter driven by its own shaft, turned away from the aisle so that the
        // face an arm feeds it through is the one the viewer can see. Placed through the Selection overload, because
        // that is the one CreateSceneBuilder marks virtual.
        scene.world().setBlocks(util.select().position(machine), AllBlocks.MECHANICAL_CRAFTER.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, MACHINE_INTAKE.getOpposite())
                .setValue(MechanicalCrafterBlock.POINTING, Pointing.UP), false);
        scene.world().setBlocks(util.select().position(shaft), AllBlocks.SHAFT.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, MACHINE_INTAKE.getAxis()), false);
        // The player's transport: a Mechanical Arm that takes from the station and deposits into the machine. Both
        // points are written before the arm ever ticks, and both target blocks already stand, so
        // ArmBlockEntity#initInteractionPoints resolves them on its first tick.
        scene.world().setBlocks(util.select().position(arm), AllBlocks.MECHANICAL_ARM.getDefaultState(), false);
        ListTag armPoints = new ListTag();
        armPoints.add(armPoint(WareworksArmInteractionPoints.WAREHOUSE_PRODUCTION.getId(), arm, station, Mode.TAKE));
        armPoints.add(armPoint(CRAFTER_POINT, arm, machine, Mode.DEPOSIT));
        scene.world().modifyBlockEntityNBT(util.select().position(arm), ArmBlockEntity.class,
                nbt -> nbt.put("InteractionPoints", armPoints.copy()));
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 2,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 1);
        Selection stations = util.select().position(terminal)
                .add(util.select().fromTo(input.getX(), input.getY(), input.getZ(),
                        input.getX(), input.getY() + 1, input.getZ()))
                .add(util.select().position(station));
        Selection machinery = util.select().position(arm)
                .add(util.select().position(machine))
                .add(util.select().position(shaft));

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);

        scene.world().showSection(stations, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(machinery, Direction.DOWN);
        scene.idle(FADE_IDLE);

        scene.overlay().showOutline(PonderPalette.GREEN, "station", util.select().position(station), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Warehouse Production feeds the machines you build next to it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(station, FRONT));
        scene.idle(TEXT_IDLE);

        // Pointing right puts the icon left of the station, where the plate is empty, instead of over its own face.
        scene.overlay().showControls(util.vector().blockSurface(station, FRONT), Pointing.RIGHT, CONTROL_TICKS)
                .rightClick();
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("Its pattern only says what one run of your machine needs and makes; nothing is used up")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(station));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.GREEN, "order", util.select().position(terminal), TEXT_TICKS);
        // Above the terminal, so the icon covers neither its screen nor the text box beside it. Right-click, because
        // that is what a player does to a terminal block: WarehouseTerminalBlock only implements useItemOn, and in
        // Create's own scenes a left-click icon on a block means punching it.
        scene.overlay().showControls(util.vector().blockSurface(terminal, Direction.UP), Pointing.DOWN, CONTROL_TICKS)
                .rightClick();
        scene.idle(7);
        scene.overlay().showText(TEXT_TICKS)
                .text("A terminal offers that result even when the warehouse holds none of it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, FRONT));
        scene.idle(TEXT_IDLE);

        // Stays up for the whole delivery below, which is far longer than one text beat. The outline marks the
        // ingredients; the box hangs by the controller, where it is clear of the aisle the crane drives through.
        scene.overlay().showOutline(PonderPalette.INPUT, "ingredients", util.select().position(ingredients),
                TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS + 140)
                .text("The controller reserves the ingredients and the crane brings them to the station")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        crane.moveTo(CranePose.at(ingredientPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(ingredientPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.OAK_LOG, INGREDIENT_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(ingredientPosition, 0, Side.LEFT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(stationPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(stationPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(station);
        crane.moveTo(CranePose.at(stationPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        // Parks before the machine beats, so the mast never stands in front of the machine it is about to feed.
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        // The arm swings from the station in the west round to the machine's aisle face, so the whole beat is a
        // movement the viewer can follow. Phases and held item follow Create's own arm scenes: reach, pick up, reach,
        // let go.
        ItemStack ingredient = new ItemStack(Items.OAK_LOG, INGREDIENT_AMOUNT);
        int grabTicks = 16;
        int settleTicks = 30;
        // Counted, so the text covers the whole swing and stops before the next one starts.
        scene.overlay().showText(2 * ARM_MOVE_TICKS + grabTicks + settleTicks)
                .text("Your own funnel, chute, belt or Mechanical Arm carries them into the machine")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(machine, FRONT));
        scene.world().instructArm(arm, ArmBlockEntity.Phase.MOVE_TO_INPUT, ItemStack.EMPTY, 0);
        scene.idle(ARM_MOVE_TICKS);
        scene.world().instructArm(arm, ArmBlockEntity.Phase.SEARCH_OUTPUTS, ingredient, -1);
        scene.idle(grabTicks);
        scene.world().instructArm(arm, ArmBlockEntity.Phase.MOVE_TO_OUTPUT, ingredient, 0);
        scene.idle(ARM_MOVE_TICKS);
        scene.world().instructArm(arm, ArmBlockEntity.Phase.SEARCH_INPUTS, ItemStack.EMPTY, -1);
        scene.effects().indicateSuccess(machine);
        scene.idle(settleTicks);

        scene.overlay().showText(TEXT_TICKS)
                .text("Wareworks delivers and collects; it never crafts anything itself")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(machine, FRONT));
        scene.idle(TEXT_IDLE);

        // Inserts through the input's DirectBeltInputBehaviour and flaps the funnel above it.
        scene.world().createItemOnBeltLike(input, Direction.UP, new ItemStack(Items.OAK_PLANKS, PRODUCT_AMOUNT));
        scene.idle(20);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "product", util.select().position(input), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The product comes back through an ordinary Warehouse Input, like any other item")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input, FRONT));
        scene.idle(TEXT_IDLE + 60);

        // Also stays up for the whole trip, so no frame of the storing is without its text.
        scene.overlay().showText(TEXT_TICKS + 120)
                .text("The crane stores it, and your waiting request is served from that stock")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.OAK_PLANKS, PRODUCT_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(productPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "stored", util.select().position(product), TEXT_TICKS);
        crane.moveTo(new CranePose(productPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(product);
        crane.moveTo(CranePose.at(productPosition, 0, Side.LEFT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(40);

        scene.markAsFinished();
    }
}
