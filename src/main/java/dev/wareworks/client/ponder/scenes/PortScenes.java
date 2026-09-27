package dev.wareworks.client.ponder.scenes;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.station.RequestFilterBehaviour;
import dev.wareworks.content.station.WarehouseOutputBlockEntity;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.crane.CranePhase;
import dev.wareworks.core.crane.CranePose;
import dev.wareworks.core.port.PortRedstone;
import dev.wareworks.core.port.PortSettings;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * Ponder scenes of the warehouse port ({@code docs/warehouse-system.md} §3.2, M17, issue #12): the two <b>directions</b>
 * a warehouse output can work in, and the three moments at which it acts.
 * <p>
 * <b>Two scenes, not one, and not a beat inside {@code warehouse/retrieving}.</b> The port carries two stories that teach
 * different things — <i>when</i> it hands items out ({@link #requesting}) and that it can take items <i>in</i>
 * ({@link #accepting}) — and told in one scene they came to well over a minute of watching. Inserting them into the
 * existing retrieving scene would additionally renumber every later {@code text_n} key of that scene in both lang files,
 * which is the same reason {@code warehouse/filters} is a scene of its own ({@link WarehouseScenes#storageFilters}).
 * {@code warehouse/retrieving} therefore keeps teaching the plain case — filter, pulse, delivery — and these two carry
 * everything M17 added.
 * <p>
 * <b>Which faces the viewer sees</b> (derived in {@link TerminalScenes}): the camera draws the <b>north</b> face of a
 * block on the left half of the screen and the <b>west</b> face on the right half. That decides which rack plane each
 * scene puts its port on, because the two scenes need different faces of it:
 * <ul>
 * <li>{@link #requesting} teaches the <b>filter slot</b> and its hold-to-edit board, which live on the top, back and
 * side faces. Its port stands on the {@link Side#RIGHT} plane with the rack position before it left empty, so the port's
 * west face — a side face, and one the camera draws — is free and readable ({@link #FRONT}).</li>
 * <li>{@link #accepting} teaches the two things that say "this port accepts": the <b>andesite</b> ring and spout of the
 * accepting model (ADR-017) and the <b>signed rank</b> {@code WarehouseOutputRenderer} paints on the <b>back</b> plate.
 * Its port therefore stands on the {@link Side#LEFT} plane, whose aisle face points away from the viewer and whose back
 * is north, i.e. straight at the camera — and the inventory column in front of it stays empty so that nothing covers
 * that plate.</li>
 * </ul>
 * <b>The lever sits on top of the port</b> in both scenes, not behind it: orthogonally adjacent, plainly visible from a
 * camera that looks down, and it covers neither the aisle opening nor the back plate (the same shape the {@code ports}
 * visual scenario uses). It is also why no funnel is drawn above the port here; what a player builds behind a port is
 * named in the text instead, and {@code warehouse/retrieving} already shows the funnel.
 * <p>
 * As in {@link WarehouseScenes}, storyboards must stay level-free: they also run with {@code level == null} during lang
 * datagen, so nothing here reads a block entity. A port's <b>direction</b> is written as both halves of what a real world
 * keeps in step by itself ({@code PonderAisle#placePort}), and its redstone behaviour as plain NBT. The order of the
 * {@code .text(...)} calls defines the {@code text_1 … text_n} lang keys of each scene id.
 * <p>
 * <b>The redstone is real block states, not an effect</b>: a {@code PonderLevel} runs no block ticks and no neighbour
 * updates, so a lever keeps whatever state a scene sets on it and {@code toggleRedstonePower} flips exactly the
 * {@code POWERED} properties of a selection — the port's own included, which is where the port would read its signal.
 */
public final class PortScenes {
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
    /** The face the viewer can read on a member of the {@link Side#RIGHT} rack plane whose predecessor is empty. */
    private static final Direction FRONT = Direction.WEST;
    /** The back of a port on the {@link Side#LEFT} rack plane: its spout side, and the plate the rank is painted on. */
    private static final Direction BACK_LEFT = Direction.NORTH;

    /** What the requesting port asks for per trip, and the number in its filter slot. */
    private static final int REQUEST_AMOUNT = 16;
    /** What arrives at the input while a stock rule refuses to store any more of it. */
    private static final int SURPLUS = 16;
    /** The overflow the accepting scene turns its port into: the weakest one, which is also the default. */
    private static final int OVERFLOW_RANK = -1;
    /** The diversion the closing beat turns it into: a rank that clearly outranks every storage location. */
    private static final int DIVERSION_RANK = 4;
    /** What the machine leaves in the chest behind the collecting port, and what the crane then stores (M18, issue #13). */
    private static final int COLLECTED_AMOUNT = 12;

    /** NBT key of Create's {@code FilteringBehaviour} count, which is what the port's "Requested Amount" board sets. */
    private static final String FILTER_AMOUNT = "FilterAmount";

    private PortScenes() {
    }

    // --- when a port hands items out ---------------------------------------------------------------------------------

    /**
     * What the port's redstone behaviour is for: one pulse hands out once, a held signal keeps a machine supplied with
     * one trip at a time and no clock, and "unless powered" turns that around (§3.2, §7.2).
     */
    public static void requesting(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_port_requesting", "Supplying a Machine from a Warehouse");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // The rack position before the port stays empty, so its west face — the one the camera draws — is free and the
        // filter slot on it can be pointed at (see the class comment).
        int portPosition = 4;
        int firstRack = 5;
        int lastRack = 6;
        // Two source locations on opposite rack planes, so the second trip is a journey of its own instead of a
        // repeat of the first and the arm is seen reaching to both sides of the aisle.
        int firstSource = 6;
        int secondSource = 5;

        BlockPos dock = aisle.dock(util);
        BlockPos port = aisle.rack(util, portPosition, 0, Side.RIGHT);
        BlockPos lever = port.above();
        ItemStack requested = new ItemStack(Items.IRON_INGOT);

        aisle.placeAisle(scene, util);
        aisle.placeOutput(scene, util, portPosition, 0, Side.RIGHT);
        scene.world().setBlock(lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.SOUTH), false);
        // A rack wall on both planes behind the port: the scene is about the port, but a lone pair of barrels on a
        // nine-block plate reads as an empty stage rather than as a warehouse.
        for (int position = firstRack; position <= lastRack; position++) {
            aisle.placeStorage(scene, util, position, 0, Side.LEFT);
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        }
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() - 2,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        Selection portColumn = util.select().fromTo(port.getX(), port.getY(), port.getZ(),
                lever.getX(), lever.getY(), lever.getZ());
        Selection signal = util.select().position(lever).add(util.select().position(port));

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(portColumn, Direction.DOWN);
        scene.idle(FADE_IDLE);

        // --- the two settings on the filter slot ---------------------------------------------------------------------
        // Both write the filtering behaviour's NBT; the filter item itself is drawn by WarehouseOutputRenderer.
        scene.world().setFilterData(util.select().position(port), WarehouseOutputBlockEntity.class, requested);
        scene.world().modifyBlockEntityNBT(util.select().position(port), WarehouseOutputBlockEntity.class,
                nbt -> nbt.putInt(FILTER_AMOUNT, REQUEST_AMOUNT));
        scene.overlay().showFilterSlotInput(util.vector().blockSurface(port, FRONT), FRONT, TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A Warehouse Output asks the warehouse for the item in its filter slot")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, FRONT));
        scene.idle(TEXT_IDLE);

        // Pointing right puts the icon left of the port, into the rack position left empty for exactly that.
        scene.overlay().showControls(util.vector().blockSurface(port, FRONT), Pointing.RIGHT, CONTROL_TICKS)
                .rightClick();
        scene.idle(CLICK_LEAD);
        scene.overlay().showText(TEXT_TICKS)
                .text("Hold Right-Click on that slot to set the amount, and on the rows when the port acts")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, FRONT));
        scene.idle(TEXT_IDLE);

        // --- on a pulse ----------------------------------------------------------------------------------------------
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        scene.world().toggleRedstonePower(signal);
        scene.effects().indicateRedstone(lever);
        // Stays up for the whole trip below, which is far longer than one text beat.
        scene.overlay().showText(TEXT_TICKS + 120)
                .text("On a pulse it asks once per signal, and the crane brings that one load")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(lever));
        scene.idle(CLICK_LEAD);
        scene.world().toggleRedstonePower(signal);
        deliver(scene, crane, firstSource, Side.LEFT, portPosition, port, Items.IRON_INGOT);

        // --- while powered -------------------------------------------------------------------------------------------
        setRedstoneMode(scene, util, port, PortRedstone.WHILE_POWERED);
        scene.world().toggleRedstonePower(signal);
        scene.effects().indicateRedstone(lever);
        scene.overlay().showText(TEXT_TICKS)
                .text("While powered it asks again by itself as soon as the last items have arrived")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(lever));
        scene.idle(TEXT_IDLE);

        scene.overlay().showOutline(PonderPalette.OUTPUT, "supplied", util.select().position(port), TEXT_TICKS + 120);
        scene.overlay().showText(TEXT_TICKS + 120)
                .text("Never more than one trip at a time, so a machine behind it stays supplied without a clock")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, FRONT));
        deliver(scene, crane, secondSource, Side.RIGHT, portPosition, port, Items.IRON_INGOT);

        // --- switching it off ----------------------------------------------------------------------------------------
        scene.world().toggleRedstonePower(signal);
        scene.effects().indicateRedstone(lever);
        scene.overlay().showText(SHORT_IDLE)
                .text("Switching the signal off stops the next trip; what is already on its way still arrives")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(lever));
        scene.idle(SHORT_IDLE);

        // --- unless powered ------------------------------------------------------------------------------------------
        setRedstoneMode(scene, util, port, PortRedstone.UNLESS_POWERED);
        scene.overlay().showOutline(PonderPalette.GREEN, "unwired", util.select().position(port), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Unless powered turns that around: the port then works unwired, and a lever switches it off")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, FRONT));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    // --- when a port takes items in ----------------------------------------------------------------------------------

    /**
     * The other direction: a wrench turns a warehouse output into a port that <b>accepts</b> what the warehouse cannot
     * keep, ranked after every storage location — the overflow a stock rule's maximum asks for (§3.2, §7.1).
     */
    public static void accepting(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_port_accepting", "An Overflow for a Warehouse");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Position 1 stays empty on the right-hand plane: the parked crane stands in front of it, and it is what makes
        // the keeper's west face readable (see StockRuleScenes).
        int keeperPosition = 2;
        int inputPosition = 3;
        // The port stands on the other plane, where its back plate faces the camera (see the class comment).
        int portPosition = 4;
        int firstRack = 5;
        int lastRack = 6;

        BlockPos dock = aisle.dock(util);
        BlockPos keeper = aisle.rack(util, keeperPosition, 0, Side.RIGHT);
        BlockPos input = aisle.rack(util, inputPosition, 0, Side.RIGHT);
        BlockPos port = aisle.rack(util, portPosition, 0, Side.LEFT);
        BlockPos lever = port.above();

        aisle.placeAisle(scene, util);
        aisle.placeStockKeeper(scene, util, keeperPosition, 0, Side.RIGHT);
        aisle.placeInput(scene, util, inputPosition, 0, Side.RIGHT);
        aisle.placeInsertingFunnelAbove(scene, input);
        // Placed as the plain output it is until the wrench beat below turns it around.
        aisle.placeOutput(scene, util, portPosition, 0, Side.LEFT);
        scene.world().setBlock(lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.SOUTH), false);
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 1,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        Selection members = util.select().position(keeper)
                .add(util.select().fromTo(input.getX(), input.getY(), input.getZ(),
                        input.getX(), input.getY() + 1, input.getZ()));
        Selection signal = util.select().position(lever).add(util.select().position(port));

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(members, Direction.DOWN);
        scene.idle(FADE_IDLE);
        // Fading north means the port moves north into place, i.e. it comes in from the aisle side.
        scene.world().showSection(util.select().position(port), Direction.NORTH);
        scene.idle(FADE_IDLE);

        // --- the wrench turns it around ------------------------------------------------------------------------------
        // Pointing right puts the icon left of the port, into the rack position left empty for it: above the port is
        // where the lever appears later, and an icon drawn there covers both the lever and the rank on the plate.
        scene.overlay().showControls(PonderAisle.portBox(util, port, BACK_LEFT), Pointing.RIGHT, CONTROL_TICKS)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.idle(CLICK_LEAD);
        aisle.setPortRank(scene, util, port, Side.LEFT, OVERFLOW_RANK);
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("Hold Right-Click on a Warehouse Output with a Wrench, and it accepts items instead of asking")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(PonderAisle.portBox(util, port, BACK_LEFT));
        scene.idle(TEXT_IDLE + 20);

        // Pointed at the top, not at the back plate: the leader line would otherwise cross the very digit the beat
        // is about (the renderer draws it high up inside that plate).
        scene.overlay().showText(SHORT_IDLE + 15)
                .text("It turns andesite where the crane reaches in, and shows its rank on the back you wire up")
                .placeNearTarget()
                .pointAt(util.vector().topOf(port));
        scene.idle(SHORT_IDLE + 15);

        // --- what the rank means ------------------------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.GREEN, "storage", storage, TEXT_TICKS);
        scene.overlay().showOutline(PonderPalette.BLUE, "overflow", util.select().position(port), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A negative rank ranks it after every storage location, so it only gets what cannot be kept")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().topOf(port));
        scene.idle(TEXT_IDLE);

        // --- and what makes one necessary ---------------------------------------------------------------------------
        // The keeper's lamp burns while any of its rules bites; a rule at its maximum is one of them.
        scene.world().setBlock(keeper, StockRuleScenes.keeperState(true, false), false);
        // Inserts through the input's DirectBeltInputBehaviour and flaps the funnel above it.
        scene.world().createItemOnBeltLike(input, Direction.UP, new ItemStack(Items.COBBLESTONE, SURPLUS));
        scene.idle(20);
        scene.overlay().showOutline(PonderPalette.RED, "at-maximum", util.select().position(keeper), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A stock rule's maximum is what makes one necessary: above it no location takes the item")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(keeper, FRONT));
        scene.idle(TEXT_IDLE);

        // --- the surplus leaves --------------------------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.OUTPUT, "surplus", util.select().position(input), TEXT_TICKS + 100);
        scene.overlay().showText(TEXT_TICKS + 100)
                .text("The surplus then leaves through the port instead of backing the input up")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(input, FRONT));
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(inputPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.COBBLESTONE, SURPLUS);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(inputPosition, 0, Side.RIGHT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(portPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(portPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(port);
        crane.moveTo(CranePose.at(portPosition, 0, Side.LEFT), CranePhase.RETRACT_TARGET);
        // Parks again, so the closing beats are not read past the crane's mast.
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.GREEN, "behind", util.select().position(port), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("Nothing is destroyed: what you build behind the port decides where the items go")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, BACK_LEFT));
        scene.idle(TEXT_IDLE);

        // --- it needs no wiring, and a lever stops it ---------------------------------------------------------------
        setRedstoneMode(scene, util, port, PortRedstone.UNLESS_POWERED);
        scene.world().showSection(util.select().position(lever), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().toggleRedstonePower(signal);
        scene.effects().indicateRedstone(lever);
        scene.overlay().showText(TEXT_TICKS)
                .text("Unless powered it needs no wiring at all, and one lever switches the overflow off")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(lever));
        scene.idle(TEXT_IDLE);
        scene.world().toggleRedstonePower(signal);
        scene.idle(10);

        // --- the other sign -----------------------------------------------------------------------------------------
        scene.overlay().showControls(PonderAisle.portBox(util, port, BACK_LEFT), Pointing.RIGHT, CONTROL_TICKS)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.idle(CLICK_LEAD);
        aisle.setPortRank(scene, util, port, Side.LEFT, DIVERSION_RANK);
        scene.overlay().showOutline(PonderPalette.BLUE, "diversion", util.select().position(port), TEXT_TICKS + 20);
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("A positive rank makes it a diversion instead: then it takes items before they are stored")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().topOf(port));
        scene.idle(TEXT_IDLE + 20);

        scene.markAsFinished();
    }

    // --- when a port fetches items in --------------------------------------------------------------------------------

    /**
     * The third direction ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13): the warehouse <b>fetches</b> instead
     * of waiting. A player points the port at the inventory a machine drops its result into, and the crane reaches through
     * the port, takes the items and stores them — so a machine needs no belt back to an input.
     * <p>
     * Its port stands on the {@link Side#LEFT} plane for the same reason the accepting one does: the face the camera draws
     * there is the <b>back</b>, which is where the copper spout of the collect model is. The <b>barrel behind it</b> is the
     * machine's output, and it stands in the inventory column of that plane, exactly where a storage location's barrel
     * would be — which is the point of the geometry: a collecting port is an interface whose inventory is not stock.
     * <p>
     * The closing beat is the one thing a player has to know before they build a loop with it: collecting stops at a stock
     * rule's maximum, so a port and an overflow can never pass the same item back and forth.
     */
    public static void collecting(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("warehouse_port_collecting", "Collecting from a Machine");

        PonderAisle aisle = PonderAisle.WIDE;
        scene.configureBasePlate(0, 0, aisle.plateSize());
        scene.scaleSceneView(0.9f);

        // Position 1 stays empty in front of the parked crane, as in the accepting scene.
        int stationPosition = 2;
        int keeperPosition = 3;
        // The port on the other plane, so its copper back faces the camera; the position before it stays empty so that
        // nothing covers that face.
        int portPosition = 4;
        int firstRack = 5;
        int lastRack = 6;

        BlockPos dock = aisle.dock(util);
        BlockPos station = aisle.rack(util, stationPosition, 0, Side.RIGHT);
        BlockPos keeper = aisle.rack(util, keeperPosition, 0, Side.RIGHT);
        BlockPos port = aisle.rack(util, portPosition, 0, Side.LEFT);
        // The machine's output: the barrel <b>behind</b> the port, which the crane reaches through the port.
        BlockPos machine = aisle.inventory(util, portPosition, 0, Side.LEFT);
        BlockPos lever = port.above();

        aisle.placeAisle(scene, util);
        aisle.placeProduction(scene, util, stationPosition, 0, Side.RIGHT);
        aisle.placeStockKeeper(scene, util, keeperPosition, 0, Side.RIGHT);
        // Placed as the plain output it is until the wrench beat below turns it around.
        aisle.placeOutput(scene, util, portPosition, 0, Side.LEFT);
        scene.world().setBlock(machine, Blocks.BARREL.defaultBlockState(), false);
        scene.world().setBlock(lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.SOUTH), false);
        for (int position = firstRack; position <= lastRack; position++)
            aisle.placeStorage(scene, util, position, 0, Side.RIGHT);
        scene.world().setKineticSpeed(util.select().everywhere(), CraneScript.PONDER_RPM);

        Selection aisleLine = util.select().fromTo(aisle.dockX() - 1, PonderAisle.FLOOR_Y, aisle.aisleZ(),
                aisle.lastRailX(), PonderAisle.FLOOR_Y, aisle.aisleZ());
        Selection storage = util.select().fromTo(aisle.dockX() + firstRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 1,
                aisle.dockX() + lastRack, PonderAisle.FLOOR_Y, aisle.aisleZ() + 2);
        Selection members = util.select().position(station).add(util.select().position(keeper));
        Selection signal = util.select().position(lever).add(util.select().position(port));

        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(aisleLine, Direction.DOWN);
        scene.world().showSection(storage, Direction.DOWN);
        scene.idle(FADE_IDLE + 5);
        scene.world().showSection(members, Direction.DOWN);
        scene.idle(FADE_IDLE);
        // Fading north means the two come in from the aisle side, port first and its machine behind it.
        scene.world().showSection(util.select().position(port), Direction.NORTH);
        scene.idle(FADE_IDLE);
        scene.world().showSection(util.select().position(machine), Direction.NORTH);
        scene.idle(FADE_IDLE);

        // --- what the machine leaves behind --------------------------------------------------------------------------
        scene.overlay().showOutline(PonderPalette.OUTPUT, "machine", util.select().position(machine), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A machine drops its result into a chest of its own, and nothing takes it from there")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().topOf(machine));
        scene.idle(TEXT_IDLE);

        // --- the wrench turns the port around ------------------------------------------------------------------------
        scene.overlay().showControls(PonderAisle.portBox(util, port, BACK_LEFT), Pointing.RIGHT, CONTROL_TICKS)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.idle(CLICK_LEAD);
        aisle.setPortRank(scene, util, port, Side.LEFT, PortSettings.COLLECT_RANK);
        scene.overlay().showText(TEXT_TICKS + 20)
                .text("The Collect row of its board turns the port around: now the warehouse fetches instead of waiting")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(PonderAisle.portBox(util, port, BACK_LEFT));
        scene.idle(TEXT_IDLE + 20);

        scene.overlay().showText(SHORT_IDLE + 15)
                .text("Copper where the crane reaches in says so, and the port reads the inventory right behind it")
                .placeNearTarget()
                .pointAt(util.vector().topOf(port));
        scene.idle(SHORT_IDLE + 15);

        // --- the crane fetches the result ----------------------------------------------------------------------------
        scene.world().createItemOnBeltLike(machine, Direction.UP, new ItemStack(Items.OAK_PLANKS, COLLECTED_AMOUNT));
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.GREEN, "collect", util.select().position(port), TEXT_TICKS + 120);
        scene.overlay().showText(TEXT_TICKS + 120)
                .text("The crane reaches through the port into that inventory and stores what it finds")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, BACK_LEFT));
        CraneScript crane = CraneScript.parkedAt(scene, dock);
        crane.moveTo(CranePose.at(portPosition, 0, Side.LEFT), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(portPosition, 0, CranePose.EXTENDED, Side.LEFT), CranePhase.EXTEND_SOURCE);
        crane.hold(Items.OAK_PLANKS, COLLECTED_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(portPosition, 0, Side.LEFT), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(firstRack, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(firstRack, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(aisle.rack(util, firstRack, 0, Side.RIGHT));
        crane.moveTo(CranePose.at(firstRack, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        // Parks again, so the closing beats are not read past the crane's mast.
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.BLUE, "loop", util.select().position(station), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("That closes the loop: the crane brings the ingredients and takes the product back")
                .attachKeyFrame()
                .colored(PonderPalette.BLUE)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(station, FRONT));
        scene.idle(TEXT_IDLE);

        // --- the filter, and when it stops ---------------------------------------------------------------------------
        scene.overlay().showText(TEXT_TICKS)
                .text("Its filter decides what is fetched at all; without one it takes whatever that side hands out")
                .attachKeyFrame()
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(port, BACK_LEFT));
        scene.idle(TEXT_IDLE);

        // The keeper's lamp burns while one of its rules bites; a rule at its maximum is one of them.
        scene.world().setBlock(keeper, StockRuleScenes.keeperState(true, false), false);
        scene.overlay().showOutline(PonderPalette.RED, "at-maximum", util.select().position(keeper), TEXT_TICKS);
        scene.overlay().showText(TEXT_TICKS)
                .text("A port collects only while the warehouse may still store: at a maximum it stops by itself")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(keeper, FRONT));
        scene.idle(TEXT_IDLE);

        // --- and a lever stops it too --------------------------------------------------------------------------------
        setRedstoneMode(scene, util, port, PortRedstone.UNLESS_POWERED);
        scene.world().showSection(util.select().position(lever), Direction.DOWN);
        scene.idle(FADE_IDLE);
        scene.world().toggleRedstonePower(signal);
        scene.effects().indicateRedstone(lever);
        scene.overlay().showText(TEXT_TICKS)
                .text("Unless powered it needs no wiring at all, and one lever stops the fetching")
                .attachKeyFrame()
                .colored(PonderPalette.RED)
                .placeNearTarget()
                .pointAt(util.vector().topOf(lever));
        scene.idle(TEXT_IDLE);

        scene.markAsFinished();
    }

    // --- shared ------------------------------------------------------------------------------------------------------

    /**
     * One full delivery trip of {@link #requesting}: from a storage location into the port on the {@link Side#RIGHT}
     * plane, and back to the parking pose so that the next beat is not read past the crane's mast.
     */
    private static void deliver(CreateSceneBuilder scene, CraneScript crane, int sourcePosition, Side sourceSide,
            int portPosition, BlockPos port, Item item) {
        crane.moveTo(CranePose.at(sourcePosition, 0, sourceSide), CranePhase.TRAVEL_TO_SOURCE);
        crane.moveTo(new CranePose(sourcePosition, 0, CranePose.EXTENDED, sourceSide), CranePhase.EXTEND_SOURCE);
        crane.hold(item, REQUEST_AMOUNT);
        crane.dwell(CranePhase.PICK, TRANSFER_TICKS);
        crane.moveTo(CranePose.at(sourcePosition, 0, sourceSide), CranePhase.RETRACT_SOURCE);
        crane.moveTo(CranePose.at(portPosition, 0, Side.RIGHT), CranePhase.TRAVEL_TO_TARGET);
        crane.moveTo(new CranePose(portPosition, 0, CranePose.EXTENDED, Side.RIGHT), CranePhase.EXTEND_TARGET);
        crane.dwell(CranePhase.DROP, 10);
        crane.release();
        crane.dwell(CranePhase.DROP, TRANSFER_TICKS);
        scene.effects().indicateSuccess(port);
        crane.moveTo(CranePose.at(portPosition, 0, Side.RIGHT), CranePhase.RETRACT_TARGET);
        crane.moveTo(CranePose.at(0, 0, Side.LEFT), CranePhase.IDLE);
        scene.idle(10);
    }

    /**
     * Writes the port's redstone behaviour, so the block a scene shows really is configured the way the text says.
     * Nothing draws it — it lives in goggles and on the value board, neither of which a Ponder scene has — but a
     * storyboard that lies about the block it builds is a storyboard that cannot be checked.
     */
    private static void setRedstoneMode(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos port,
            PortRedstone mode) {
        scene.world().modifyBlockEntityNBT(util.select().position(port), WarehouseOutputBlockEntity.class,
                nbt -> nbt.putString(RequestFilterBehaviour.REDSTONE_MODE_TAG, mode.name()));
    }
}
