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
import dev.wareworks.content.station.WarehouseStockKeeperBlock;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.registry.WareworksArmInteractionPoints;
import dev.wareworks.registry.WareworksBlocks;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * Ponder scenes of the warehouse stock keeper ({@code docs/warehouse-system.md} §3.6, M15, ADR-027).
 * <p>
 * <b>Two scenes, not one.</b> The keeper carries two stories, and they teach different things: what the three numbers
 * <i>are</i> (each one governs a different direction), and what the minimum <i>does</i> on its own (it orders
 * production, and stops after a lost batch). Told in one scene they came to a minute of watching in which the three
 * numbers — the part a player needs before anything else — were over after the first third. They are therefore
 * {@link #stockRules} and {@link #restocking}, which Ponder pages through with the arrow keys, exactly as the terminal's
 * two scenes are split into placing and requesting (ADR-016).
 * <p>
 * Each scene spends one beat per thing it teaches, and every beat points at the block that number moves: the keeper's
 * own comparator and lamp for the minimum, a warehouse input that backs up for the maximum, a redstone request at an
 * output for the reserve — and in the second scene the production station, the player's machine and the input the
 * product comes back through.
 * <p>
 * As in {@link WarehouseScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen, so nothing here reads a block entity. The keeper's two lamps are set as <b>block states</b>
 * ({@link WarehouseStockKeeperBlock#LIT}, {@link WarehouseStockKeeperBlock#PAUSED}), which is exactly what the
 * controller's rule tick writes in a real world. The order of the {@code .text(...)} calls defines the
 * {@code text_1 … text_n} lang keys of each scene id.
 * <p>
 * <b>Which faces the viewer sees</b> (derived in {@link TerminalScenes}): the camera draws the <b>north</b> face of a
 * block on the left half and the <b>west</b> face on the right half, so a member on the {@link Side#RIGHT} rack plane
 * shows its aisle face (north) and its west face. {@link #FRONT} is that readable face — but only while the rack
 * position <i>before</i> it is empty, because a neighbouring member stands against exactly that face. In a dense row
 * every member shows its aisle face and nothing else, which is why {@link #stockRules} puts the terminal at the end of
 * its row with a gap in front of it: its screen lives on the west face, and next to the output it was a wooden box.
 * <p>
 * <b>What a scene cannot show.</b> A station's buffer has no renderer, so the items a warehouse input really holds
 * while a maximum refuses them are invisible; that beat is carried by the outline and the text, exactly as the product
 * arriving is in {@link ProductionScenes}. The same is true, more quietly, of the keeper's own lamp: it is a thin
 * strip on its panel and cannot carry a beat at this distance, which is what the comparator and the redstone lamp
 * behind it are for.
 * <p>
 * <b>No screen is ever opened.</b> {@code WarehouseStockKeeperBlockEntity#openScreen} needs a {@code ServerPlayer} and
 * a {@code PonderLevel} is client-side, so the rule editor is <i>represented</i> with {@code showControls} icons plus
 * text, the same way {@link TerminalScenes} and {@link ProductionScenes} do it.
 * <p>
 * <b>The redstone is real block states, not an effect.</b> A {@code PonderLevel} runs no block ticks and no neighbour
 * updates ({@code SchematicLevel}), so a comparator, a lamp and a lever keep whatever state a scene sets on them;
 * {@code toggleRedstonePower} flips exactly the {@code POWERED}/{@code POWER}/{@code LIT} properties of a selection.
 * The keeper is never in such a selection — its own {@code LIT} is a rule lamp and is written on its own.
 */
public final class StockRuleScenes {
    private static final int TEXT_TICKS = 70;
    private static final int TEXT_IDLE = 80;
    /** A beat that only adds half a sentence to the one before it; long enough to read, short enough not to drag. */
    private static final int SHORT_IDLE = 60;
    private static final int FADE_IDLE = 15;
    private static final int CONTROL_TICKS = 40;
    /** Ticks between a control icon appearing and the change it stands for, everywhere in these scenes. */
    private static final int CLICK_LEAD = 7;
    /** How long the crane holds a fully extended arm while it transfers; long enough to read (and to screenshot). */
    private static final int TRANSFER_TICKS = 40;
    /** How long one arm movement needs: {@code ArmBlockEntity} advances by {@code speed / 1024} per tick. */
    private static final int ARM_MOVE_TICKS = 34;
    /** The face the viewer can read on a member of the {@link Side#RIGHT} rack plane (see the class comment). */
    private static final Direction FRONT = Direction.WEST;
    /** The terminal's screen face as the block state stores it: one quarter counter-clockwise from a northward port. */
    private static final TerminalDisplaySide SCREEN_SIDE = TerminalDisplaySide.LEFT;
    /**
     * The face of the player's machine the arm reaches through: its aisle side, the one the camera draws
     * ({@link ProductionScenes} derives it).
     */
    private static final Direction MACHINE_INTAKE = PonderAisle.outward(Side.RIGHT).getOpposite();
    /** Create's arm interaction point type of a Mechanical Crafter; the station's own type comes from the registry. */
    private static final ResourceLocation CRAFTER_POINT = Create.asResource("crafter");

    /** The rule of both scenes: keep planks in stock, out of logs, one log per run. */
    private static final int LOGS_PER_RUN = 1;
    private static final int PLANKS_PER_RUN = 4;
    /** Runs of the player's machine one automatic order is worth, so the numbers in the scene add up. */
    private static final int RUNS = 3;
    /** The items that pile up in the input while the maximum refuses them. */
    private static final int BACKED_UP = 16;
    /** What a redstone request at the output is served with once the reserve has been taken off it. */
    private static final int OUT_TO_AUTOMATION = 16;

    private StockRuleScenes() {
    }

    /**
     * The keeper's block state with its two lamps, facing the aisle exactly as
     * {@code PonderAisle#placeStockKeeper} places it. A storyboard cannot read a state back out of the scene, so the
     * state it wants is built from scratch.
     *
     * @param lit    any rule of the keeper bites (below its minimum, at its maximum or down to its reserve)
     * @param paused the safety stop is holding a rule, which is the other lamp and outranks the first
     */
    private static BlockState keeperState(boolean lit, boolean paused) {
        return WareworksBlocks.WAREHOUSE_STOCK_KEEPER.getDefaultState()
                .setValue(WarehouseStockKeeperBlock.FACING, PonderAisle.outward(Side.RIGHT).getOpposite())
                .setValue(WarehouseStockKeeperBlock.LIT, lit)
                .setValue(WarehouseStockKeeperBlock.PAUSED, paused);
    }

    // --- the three numbers -------------------------------------------------------------------------------------------

    /** What a stock rule is: one item and three numbers, each governing a different direction (§3.6). */
    public static void stockRules(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_stock_rules", "Stock Rules of a Warehouse");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Position 1 stays empty on this plane: the parked crane stands in front of it and would hide it.
        //
        // A row of members hides its own faces: the camera draws a block's north and west faces, and the west face of
        // a member is the wall its neighbour one position lower stands against. Only a member whose predecessor is
        // empty shows two faces. That is free for the keeper (position 1 is empty) and is why the terminal — the one
        // member here whose screen is on its west face (ADR-022) — stands at the end of the row with a gap in front
        // of it, instead of next to the output where it would be a plain wooden box.
        int keeperPosition = 2;
        int inputPosition = 3;
        int outputPosition = 4;
        int terminalPosition = 6;
        int firstRack = 4;
        int lastRack = 6;
        // The far end of the left-hand racks holds what the redstone request asks for, so the crane crosses the whole
        // stage before it reaches the output and the beat has something to watch.
        int sourcePosition = 6;

        BlockPos dock = aisle.dock(util);
        BlockPos keeper = aisle.rack(util, keeperPosition, 0, Side.RIGHT);
        BlockPos input = aisle.rack(util, inputPosition, 0, Side.RIGHT);
        BlockPos terminal = aisle.rack(util, terminalPosition, 0, Side.RIGHT);
        BlockPos output = aisle.rack(util, outputPosition, 0, Side.RIGHT);
        BlockPos source = aisle.rack(util, sourcePosition, 0, Side.LEFT);
        // The player's own redstone, all of it behind the rack plane where nothing else stands: a comparator reading
        // the keeper (a comparator reads the block behind it, i.e. opposite its FACING) with a lamp behind that, and
        // the lever that pulses the output.
        BlockPos comparator = aisle.inventory(util, keeperPosition, 0, Side.RIGHT);
        BlockPos lamp = comparator.relative(PonderAisle.outward(Side.RIGHT));
        BlockPos lever = aisle.inventory(util, outputPosition, 0, Side.RIGHT);

        aisle.placeAisle(scene, util);
        aisle.placeStockKeeper(scene, util, keeperPosition, 0, Side.RIGHT);
        aisle.placeInput(scene, util, inputPosition, 0, Side.RIGHT);
        aisle.placeInsertingFunnelAbove(scene, input);
        aisle.placeTerminal(scene, util, terminalPosition, 0, Side.RIGHT, SCREEN_SIDE);
        aisle.placeOutput(scene, util, outputPosition, 0, Side.RIGHT);
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
        scene.world().setBlock(comparator, Blocks.COMPARATOR.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, PonderAisle.outward(Side.RIGHT))
                .setValue(ComparatorBlock.POWERED, false), false);
        scene.world().setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), false);
        scene.world().setBlock(lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.SOUTH), false);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 2,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 1);
        Selection members = util.select().position(keeper)
                .add(util.select().fromTo(input.getX(), input.getY(), input.getZ(),
                        input.getX(), input.getY() + 1, input.getZ()))
                .add(util.select().position(terminal))
                .add(util.select().position(output));
        Selection signal = util.select().position(comparator).add(util.select().position(lamp));

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(members, Direction.DOWN);
        scene.idle(FADE_IDLE);

        // --- what the block is ---------------------------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.GREEN, "keeper", util.select().position(keeper), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Warehouse Stock Keeper holds the stock rules of its aisle")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(keeper, FRONT));
        scene.idle(TEXT_IDLE);

        // Pointing right puts the icon left of the keeper, where the plate is empty, instead of over its own face.
        scene.overlay().showControls(util.vector().blockSurface(keeper, FRONT), Pointing.RIGHT, CONTROL_TICKS)
                .rightClick();
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("One row is one item and three numbers, and each number governs a different direction")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(keeper));
        scene.idle(TEXT_IDLE);

        // --- the minimum ---------------------------------------------------------------------------------------------
        // The lamp on the keeper burns while any of its rules bites; the comparator and the player's own lamp behind
        // it are what a rule below its minimum really drives.
        scene.world().showSection(signal, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().setBlock(keeper, keeperState(true, false), false);
        scene.world().toggleRedstonePower(signal);
        scene.effects().indicateRedstone(comparator);
        scene.overlay().showText(TEXT_TICKS)
                .text("The minimum is what comes in: below it a comparator on the keeper calls for the item")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(comparator, Direction.UP));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.RED, "lamp", util.select().position(lamp), SHORT_IDLE);
        scene.overlay().showText(SHORT_IDLE)
                .text("A farm of yours then runs exactly as long as the warehouse needs it")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(lamp, Direction.UP));
        scene.idle(SHORT_IDLE);

        // --- the maximum ---------------------------------------------------------------------------------------------
        // Inserts through the input's DirectBeltInputBehaviour and flaps the funnel above it: items arrive, and this
        // time nobody comes to fetch them.
        scene.world().createItemOnBeltLike(input, Direction.UP, new ItemStack(Items.COBBLESTONE, BACKED_UP));
        scene.idle(20);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "backed-up", util.select().position(input), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The maximum is what may be stored: above it the crane stops accepting the item")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(SHORT_IDLE + 15)
                .text("A Warehouse Input then backs up on purpose: that is the rule working, not a jam")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input.above(), Direction.UP));
        scene.idle(SHORT_IDLE + 15);

        // --- the reserve ---------------------------------------------------------------------------------------------
        scene.world().showSection(util.select().position(lever), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().toggleRedstonePower(util.select().position(lever).add(util.select().position(output)));
        scene.effects().indicateRedstone(lever);
        // Stays up for the whole way to the rack, which is far longer than one text beat.
        scene.overlay().showOutline(PonderPalette.BLUE, "reserve", util.select().position(source), TEXT_TICKS + 55);
        scene.overlay().showText(TEXT_TICKS + 55)
                .text("The reserve is what may go out to automation: a redstone request stops at the last items")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().topOf(lever));

        CraneScript crane = CraneScript.parkedAt(scene, dock);
        crane.moveTo(CranePose.at(sourcePosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(sourcePosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        // Only what is above the reserve is ever picked up: the request was for more, the last items stay in the rack.
        crane.hold(Items.COPPER_INGOT, OUT_TO_AUTOMATION);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(sourcePosition, 0, Side.LEFT), CranePhase.RETRACT_SOURCE);

        // The second half of the trip gets its own line, so that no part of the delivery plays without text and the
        // rack the crane is driving away from is named while it still has items in it.
        scene.overlay().showOutline(PonderPalette.BLUE, "left-behind", util.select().position(source), TEXT_TICKS + 40);
        scene.overlay().showText(TEXT_TICKS + 80)
                .text("Only what is above it is fetched; the last ones stay in the rack")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(source, PonderAisle.outward(Side.LEFT)));
        crane.moveTo(CranePose.at(outputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(outputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(output);
        crane.moveTo(CranePose.at(outputPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.world().toggleRedstonePower(util.select().position(lever).add(util.select().position(output)));
        scene.idle(10);

        // --- and who the reserve does not stop -----------------------------------------------------------------------
        // The closing beat is deliberately the longest text of the scene: it is the one sentence a player has to keep
        // (the reserve is aimed at the warehouse's own automation, never at them), and it is the last thing on screen.
        scene.overlay().showControls(util.vector().blockSurface(terminal, FRONT), Pointing.LEFT, CONTROL_TICKS)
                .rightClick();
        scene.idle(CLICK_LEAD);
        scene.overlay().showOutline(PonderPalette.GREEN, "terminal", util.select().position(terminal),
                TEXT_TICKS + 40);
        scene.overlay().showText(TEXT_TICKS + 40)
                .text("You are never stopped: a terminal serves you down to the last one and asks before it does")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(terminal, FRONT));
        scene.idle(TEXT_IDLE + 40);

        scene.markAsFinished();
    }

    // --- the minimum drives production -------------------------------------------------------------------------------

    /**
     * What the minimum does on its own: the warehouse orders the item from a pattern of its own aisle, and stops
     * ordering when a machine swallows a batch (§3.6.3, §3.6.4).
     */
    public static void restocking(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_restocking", "A Warehouse that Restocks Itself");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Same row as in ProductionScenes, so the two scenes read as the same warehouse: station, then the player's
        // own arm and machine, with the keeper where the terminal stands there.
        int keeperPosition = 2;
        int inputPosition = 3;
        int stationPosition = 4;
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
        BlockPos keeper = aisle.rack(util, keeperPosition, 0, Side.RIGHT);
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
        aisle.placeStockKeeper(scene, util, keeperPosition, 0, Side.RIGHT);
        aisle.placeInput(scene, util, inputPosition, 0, Side.RIGHT);
        aisle.placeInsertingFunnelAbove(scene, input);
        aisle.placeProduction(scene, util, stationPosition, 0, Side.RIGHT);
        // The player's machine and its transport, exactly as ProductionScenes builds them: placed through the
        // Selection overloads, because those are the ones CreateSceneBuilder marks virtual, and with both arm points
        // written before the arm ever ticks, so ArmBlockEntity#initInteractionPoints resolves them on its first tick.
        scene.world().setBlocks(util.select().position(machine), AllBlocks.MECHANICAL_CRAFTER.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, MACHINE_INTAKE.getOpposite())
                .setValue(MechanicalCrafterBlock.POINTING, Pointing.UP), false);
        scene.world().setBlocks(util.select().position(shaft), AllBlocks.SHAFT.getDefaultState()
                .setValue(RotatedPillarKineticBlock.AXIS, MACHINE_INTAKE.getAxis()), false);
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
        Selection members = util.select().position(keeper)
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
        scene.world().showSection(members, Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().showSection(machinery, Direction.DOWN);
        scene.idle(FADE_IDLE);

        // --- a rule that is short, and a pattern that could fill it --------------------------------------------------
        scene.world().setBlock(keeper, keeperState(true, false), false);
        scene.overlay().showOutline(PonderPalette.RED, "short", util.select().position(keeper), TEXT_TICKS);
        scene.overlay().showOutline(PonderPalette.GREEN, "pattern", util.select().position(station), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("This aisle holds fewer than a rule's minimum, and a station here has a pattern for the item")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(keeper, FRONT));
        scene.idle(TEXT_IDLE);

        // --- the warehouse orders it by itself -----------------------------------------------------------------------
        // The text hangs by the controller, where it is clear of the aisle the crane is about to drive through, and
        // stays up for the whole trip to the ingredients.
        scene.overlay().showOutline(PonderPalette.INPUT, "ingredients", util.select().position(ingredients),
                TEXT_TICKS + 40);
        scene.overlay().showText(TEXT_TICKS + 55)
                .text("So the warehouse orders it by itself: the same production order, with nobody asking for it")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));

        CraneScript crane = CraneScript.parkedAt(scene, dock);
        crane.moveTo(CranePose.at(ingredientPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(ingredientPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.OAK_LOG, LOGS_PER_RUN * RUNS);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(ingredientPosition, 0, Side.LEFT), CranePhase.RETRACT_SOURCE);

        // The second half of the trip carries the one sentence that makes the reserve and the restocking one rule, and
        // is sized to it: a crane crossing the whole stage without a line on screen reads as a pause in the story.
        scene.overlay().showText(TEXT_TICKS + 80)
                .text("Never out of a reserve: with no ingredients it may spend, the rule waits and says so")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(station, FRONT));
        crane.moveTo(CranePose.at(stationPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(stationPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(station);
        crane.moveTo(CranePose.at(stationPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        // Parks before the machine beat, so the mast never stands in front of the machine it has just fed.
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        // --- the player's machine ------------------------------------------------------------------------------------
        ItemStack ingredient = new ItemStack(Items.OAK_LOG, LOGS_PER_RUN * RUNS);
        int grabTicks = 16;
        int settleTicks = 30;
        // Counted, so the text covers the whole swing and stops before the next beat starts.
        scene.overlay().showText(2 * ARM_MOVE_TICKS + grabTicks + settleTicks)
                .text("Your own machine makes the product, exactly as it does for an order of yours")
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

        // --- and the product comes back the ordinary way ---------------------------------------------------------------
        scene.world().createItemOnBeltLike(input, Direction.UP, new ItemStack(Items.OAK_PLANKS, PLANKS_PER_RUN * RUNS));
        scene.idle(20);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "product", util.select().position(input), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("The product returns through an ordinary Warehouse Input, like any other item")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showText(TEXT_TICKS + 180)
                .text("An ordinary job stores it, the minimum is met again and the lamp goes out")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(controller));
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.OAK_PLANKS, PLANKS_PER_RUN * RUNS);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(productPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "stored", util.select().position(product), TEXT_TICKS);
        crane.moveTo(new CranePose(productPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(product);
        scene.world().setBlock(keeper, keeperState(false, false), false);
        crane.moveTo(CranePose.at(productPosition, 0, Side.LEFT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(20);

        // --- the safety stop -------------------------------------------------------------------------------------------
        // Later, the same rule is short again and its order is lost in the machine: both lamps, because a paused rule
        // keeps calling for its item exactly as it did before (§3.6.4).
        scene.world().setBlock(keeper, keeperState(true, true), false);
        scene.overlay().showOutline(PonderPalette.RED, "paused", util.select().position(keeper), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("If a machine swallows a batch and nothing comes back, that rule stops ordering")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(keeper, FRONT));
        scene.idle(TEXT_IDLE);

        scene.overlay().showControls(util.vector().blockSurface(keeper, FRONT), Pointing.RIGHT, CONTROL_TICKS)
                .rightClick();
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("Check your machine, then click the rule's mark to let it order again")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(keeper));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    /**
     * One saved arm interaction point, in the shape {@code ArmInteractionPoint#serialize} writes and
     * {@code ArmBlockEntity#initInteractionPoints} reads back (see {@link ProductionScenes}): the registered type id,
     * the target relative to the arm, and the mode. Level-free, so a storyboard may build it.
     */
    private static CompoundTag armPoint(ResourceLocation type, BlockPos arm, BlockPos target, Mode mode) {
        CompoundTag point = new CompoundTag();
        point.putString("Type", type.toString());
        point.put("Pos", NbtUtils.writeBlockPos(target.subtract(arm)));
        NBTHelper.writeEnum(point, "Mode", mode);
        return point;
    }
}
